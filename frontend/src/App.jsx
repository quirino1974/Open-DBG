import React, { createContext, useCallback, useContext, useEffect, useId, useMemo, useRef, useState } from 'react';
import { CHECKS } from './checks.js';

const EMPTY_FORM = { id: '', name: '', kind: 'server', host: '', port: 3306, user: '', password: '', database: '', galeraCluster: '', ambiente: '', sito: '', insight: false };

/* Ambienti dei server: etichetta e colore. Vuoto = non definito. */
const AMBIENTI = [
  { id: 'produzione', label: 'Produzione' },
  { id: 'certificazione', label: 'Certificazione' },
  { id: 'collaudo', label: 'Collaudo' },
];
const etichettaAmbiente = (id) => AMBIENTI.find((a) => a.id === id)?.label || 'Non definito';

function AmbienteBadge({ valore }) {
  return <span className={`amb amb-${valore || 'nessuno'}`}>{etichettaAmbiente(valore)}</span>;
}

async function api(path, options = {}) {
  const res = await fetch('/api' + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  // sessione scaduta: torna alla maschera di accesso (il login stesso risponde 401 se sbagliato)
  if (res.status === 401 && path !== '/login') window.dispatchEvent(new Event('dbg-auth-expired'));
  if (!res.ok) throw new Error(data.error || `Errore HTTP ${res.status}`);
  return data;
}

function fmt(v) {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? 'Sì' : 'No';
  if (Array.isArray(v)) return v.length ? v.join(', ') : '—';
  return String(v);
}

/* Data e ora leggibili da un valore dell'API: millisecondi (Timestamp) oppure testo ISO. */
function fmtData(v) {
  if (v === null || v === undefined || v === '') return '—';
  const d = typeof v === 'number' ? new Date(v) : new Date(String(v).replace(' ', 'T'));
  return Number.isNaN(d.getTime()) ? String(v) : d.toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'medium' });
}

function formatBytes(b) {
  if (b === null || b === undefined) return '—';
  const n = Number(b);
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  return `${(n / 1024 ** 3).toFixed(2)} GB`;
}

function Badge({ value }) {
  const cls = {
    ONLINE: 'ok', OK: 'ok', INFO: 'ok', PRIMARY: 'primary', SECONDARY: 'neutral',
    DEGRADED: 'warn', WARNING: 'warn', 'SENZA QUORUM': 'bad', OFFLINE: 'bad', CRITICAL: 'bad',
    RECOVERING: 'warn',
  }[value] || 'neutral';
  return <span className={`badge ${cls}`}>{fmt(value)}</span>;
}

function Table({ rows, columns, empty = 'Nessun dato', onRowClick }) {
  if (!rows || rows.length === 0) return <p className="muted">{empty}</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead><tr>{columns.map((c) => <th key={c.key}>{c.label}</th>)}</tr></thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className={onRowClick ? 'clickable' : ''} onClick={() => onRowClick && onRowClick(row)}>
              {columns.map((c) => (
                <td key={c.key}>{c.render ? c.render(row[c.key], row) : fmt(row[c.key])}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Section({ title, children, right }) {
  return (
    <section className="card">
      <div className="card-head">
        <h3>{title}</h3>
        {right}
      </div>
      {children}
    </section>
  );
}

/* ----------------------------------------------------------- CONFIGURAZIONE */

/* Chiude la finestra con Esc. */
function useEscape(onClose) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);
}

/* Conferma interna all'interfaccia (sostituisce window.confirm); il focus parte dall'annulla. */
function ConfirmDialog({ messaggio, etichetta = 'Elimina', onConfirm, onCancel }) {
  useEscape(onCancel);
  return (
    <div className="modal-backdrop" onMouseDown={onCancel}>
      <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" onMouseDown={(e) => e.stopPropagation()}>
        <h3 id="confirm-title">Conferma</h3>
        <p>{messaggio}</p>
        <div className="modal-actions">
          <button type="button" className="secondary" onClick={onCancel} autoFocus>Annulla</button>
          <button type="button" className="danger" onClick={onConfirm}>{etichetta}</button>
        </div>
      </div>
    </div>
  );
}

function ServerForm({ initial, onSaved, onCancel }) {
  // I controlli arrivano dall'anagrafica come testo ("connessioni,indici"; "nessuno"; vuoto = tutti)
  const [form, setForm] = useState(() => {
    const c = initial.controlli;
    const controlli = Array.isArray(c) ? c : typeof c === 'string' ? (c === '' ? undefined : c === 'nessuno' ? [] : c.split(',')) : undefined;
    return { ...initial, controlli };
  });
  const [siti, setSiti] = useState([]);
  useEffect(() => { api('/siti').then(setSiti).catch(() => setSiti([])); }, []);
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { setForm(initial); setMsg(null); }, [initial]);

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function testForm() {
    setBusy(true); setMsg(null);
    try {
      const r = await api('/servers/test', { method: 'POST', body: form });
      setMsg({ ok: r.ok, text: r.ok ? `Connessione riuscita (${r.ms} ms) - ${r.edizione} ${r.version}` : `Errore: ${r.message}` });
    } catch (e) { setMsg({ ok: false, text: e.message }); }
    setBusy(false);
  }

  async function save(e) {
    e.preventDefault();
    setBusy(true); setMsg(null);
    try {
      const body = { ...form, port: Number(form.port) };
      const saved = form.id
        ? await api(`/servers/${form.id}`, { method: 'PUT', body })
        : await api('/servers', { method: 'POST', body });
      onSaved(saved);
    } catch (err) { setMsg({ ok: false, text: err.message }); }
    setBusy(false);
  }

  return (
    <form className="card" onSubmit={save}>
      <h3>{form.id ? 'Modifica server' : 'Nuovo server'}</h3>
      <div className="grid">
        <label>Nome<input value={form.name} onChange={set('name')} required /></label>
        <label>
          Tipo
          <select value={form.kind} onChange={(e) => {
            const k = e.target.value;
            // porta predefinita secondo il tipo, solo se non è stata cambiata a mano
            const porta = k === 'postgres' && form.port === 3306 ? 5432
              : k === 'sqlserver' && (form.port === 3306 || form.port === 5432) ? 1433
              : k !== 'postgres' && k !== 'sqlserver' && (form.port === 5432 || form.port === 1433) ? 3306 : form.port;
            setForm({ ...form, kind: k, port: porta });
          }}>
            <option value="server">Server MySQL / MariaDB</option>
            <option value="postgres">PostgreSQL</option>
            <option value="sqlserver">SQL Server</option>
            <option value="router">Endpoint router</option>
            <option value="monitor">Database del monitor</option>
          </select>
        </label>
        <label>Host<input value={form.host} onChange={set('host')} placeholder="mon_mysql01" required /></label>
        <label>Porta<input type="number" value={form.port} onChange={set('port')} /></label>
        <label>Utente<input value={form.user} onChange={set('user')} required /></label>
        <label>
          Password
          <input type="password" value={form.password} onChange={set('password')}
            placeholder={form.id ? '(invariata se vuota)' : ''} />
        </label>
        <label>Database (facoltativo)<input value={form.database} onChange={set('database')} /></label>
        <label>
          Ambiente
          <select value={form.ambiente || ''} onChange={set('ambiente')}>
            <option value="">Non definito</option>
            {AMBIENTI.map((a) => <option key={a.id} value={a.id}>{a.label}</option>)}
          </select>
        </label>
        <label>
          Sito
          <select value={form.sito || ''} onChange={set('sito')}>
            <option value="">Non assegnato</option>
            {siti.map((x) => <option key={x.id} value={x.nome}>{x.nome}</option>)}
            {form.sito && !siti.some((x) => x.nome === form.sito) && <option value={form.sito}>{form.sito}</option>}
          </select>
        </label>
        <label>
          Cluster Galera (facoltativo)
          <input value={form.galeraCluster || ''} onChange={set('galeraCluster')} placeholder="monMaria"
            title="Nome del cluster Galera (wsrep_cluster_name): i nodi con lo stesso nome formano un insieme" />
        </label>
        <label>
          Sorgente risorse (CPU e memoria)
          <select value={form.resourceMode || 'container'} onChange={set('resourceMode')}>
            <option value="container">Container Docker</option>
            <option value="host">Host fisico (node_exporter)</option>
            <option value="none">Nessuna</option>
          </select>
        </label>
        <label className="checkbox-riga">
          <input type="checkbox" checked={!!form.insight} disabled={form.kind === 'router' || form.kind === 'monitor'}
            onChange={(e) => setForm({ ...form, insight: e.target.checked })} />
          Includi nel report Insight (health check giornaliero)
        </label>
        {form.resourceMode === 'host' && (
          <label>
            URL metriche host
            <input value={form.metricsUrl || ''} onChange={set('metricsUrl')} placeholder="http://nodo-fisico:9100/metrics" />
          </label>
        )}
        {CONTROLLI_PER_TECNOLOGIA[form.kind] && (
          <fieldset className="controlli-server">
            <legend>Controlli da eseguire</legend>
            <p className="muted small">Solo i controlli selezionati vengono eseguiti su questo server.</p>
            {CONTROLLI_PER_TECNOLOGIA[form.kind].map((c) => {
              // Valore sempre come elenco: vuoto/assente = tutti, "nessuno" = nessuno, testo = chiavi separate da virgola
              const tutti = CONTROLLI_PER_TECNOLOGIA[form.kind].map((x) => x.k);
              const cv = form.controlli;
              const attivi = Array.isArray(cv) ? cv
                : typeof cv === 'string' && cv.trim() !== '' ? (cv === 'nessuno' ? [] : cv.split(',').map((x) => x.trim()))
                : tutti;
              return (
                <label key={c.k} className="checkbox-riga">
                  <input type="checkbox" checked={attivi.includes(c.k)}
                    onChange={(e) => setForm({ ...form, controlli: e.target.checked
                      ? [...attivi.filter((x) => x !== c.k), c.k]
                      : attivi.filter((x) => x !== c.k) })} />
                  {c.l}
                </label>
              );
            })}
          </fieldset>
        )}
      </div>
      <div className="actions">
        <button type="button" onClick={testForm} disabled={busy}>Testa connessione</button>
        <button type="submit" disabled={busy}>{form.id ? 'Salva modifiche' : 'Aggiungi'}</button>
        {form.id && <button type="button" className="secondary" onClick={onCancel}>Annulla</button>}
      </div>
      {msg && <p className={msg.ok ? 'ok-text' : 'bad-text'}>{msg.text}</p>}
    </form>
  );
}

function ConfigTab({ onChanged }) {
  const [servers, setServers] = useState([]);
  const [editing, setEditing] = useState(null);
  const [daEliminare, setDaEliminare] = useState(null);
  const [status, setStatus] = useState({});
  const [history, setHistory] = useState([]);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try {
      setServers(await api('/servers'));
      setHistory(await api('/config-log'));
    } catch (e) { setError(e.message); }
  }, []);

  useEffect(() => { load(); }, [load]);

  async function testServer(s) {
    setStatus((st) => ({ ...st, [s.id]: { testing: true } }));
    try {
      const r = await api(`/servers/${s.id}/test`, { method: 'POST' });
      setStatus((st) => ({ ...st, [s.id]: r }));
    } catch (e) { setStatus((st) => ({ ...st, [s.id]: { ok: false, message: e.message } })); }
  }

  function remove(s) {
    setDaEliminare(s);
  }

  async function eliminaConfermato() {
    const s = daEliminare;
    setDaEliminare(null);
    try {
      await api(`/servers/${s.id}`, { method: 'DELETE' });
      load(); onChanged();
    } catch (e) { setError(e.message); }
  }

  return (
    <>
      {error && <p className="bad-text banner">{error}</p>}
      <Section title="Anagrafica server" right={<button onClick={() => setEditing(EMPTY_FORM)}>+ Nuovo server</button>}>
        <Table rows={servers} columns={[
          { key: 'name', label: 'Nome' },
          { key: 'ambiente', label: 'Ambiente', render: (v) => <AmbienteBadge valore={v} /> },
          { key: 'sito', label: 'Sito', render: (v) => v || '—' },
          { key: 'insight', label: 'Insight', render: (v) => (v ? 'Sì' : '—') },
          { key: 'kind', label: 'Tipo', render: (v) => (v === 'router' ? 'Endpoint router' : v === 'monitor' ? 'Database del monitor' : v === 'postgres' ? 'PostgreSQL' : v === 'sqlserver' ? 'SQL Server' : 'Server MySQL / MariaDB') },
          { key: 'jdbcUrl', label: 'JDBC', render: (v) => <code className="jdbc">{v}</code> },
          { key: 'user', label: 'Utente' },
          { key: 'id', label: 'Test', render: (v, row) => {
              const st = status[v];
              if (!st) return <span className="muted">—</span>;
              if (st.testing) return <span className="muted">in corso…</span>;
              return <span className={st.ok ? 'ok-text' : 'bad-text'} title={st.message}>{st.ok ? `OK ${st.ms} ms` : 'Errore'}</span>;
            } },
          { key: 'azioni', label: 'Azioni', render: (v, row) => (
              <div className="actions inline">
                <button onClick={() => testServer(row)}>Testa</button>
                <button className="secondary" onClick={() => setEditing({ ...row, password: '' })}>Modifica</button>
                <button className="danger" onClick={() => remove(row)}>Elimina</button>
              </div>
            ) },
        ]} empty="Nessun server in anagrafica" />
      </Section>
      {editing && (
        <div className="modal-backdrop" onMouseDown={() => setEditing(null)}>
          <div className="modal wide" role="dialog" aria-modal="true" onMouseDown={(e) => e.stopPropagation()}>
            <ServerForm
              initial={editing}
              onSaved={() => { setEditing(null); load(); onChanged(); }}
              onCancel={() => setEditing(null)}
            />
          </div>
        </div>
      )}
      {daEliminare && (
        <ConfirmDialog messaggio={`Eliminare il server "${daEliminare.name}" dall'anagrafica?`}
          onConfirm={eliminaConfermato} onCancel={() => setDaEliminare(null)} />
      )}
      <Section title="Storico delle configurazioni">
        <Table rows={history} columns={[
          { key: 'changed_at', label: 'Quando', render: (v) => fmtData(v) },
          { key: 'action', label: 'Azione' },
          { key: 'server_name', label: 'Server' },
          { key: 'details', label: 'Dettagli' },
        ]} empty="Nessuna modifica registrata" />
      </Section>
    </>
  );
}

/* ----------------------------------------------------------------- DASHBOARD */

/* Icone stilizzate per motore: MariaDB e MySQL hanno forme e colori distinti. */
function EngineIcon({ engine }) {
  if (!engine) return null;
  const isMaria = engine === 'MariaDB';
  return (
    <span className={`engine-icon ${isMaria ? 'maria' : 'mysql'}`} title={engine} aria-label={engine}>
      <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
        {isMaria ? (
          <>
            <path d="M12 2 21 7v10l-9 5-9-5V7z" fill="#003545" />
            <path d="M6 9h2.2l1.8 3.2L11.8 9H14v6h-1.8v-3.6l-1.6 2.6h-.9l-1.6-2.6V15H6z" fill="#C0765A" />
            <circle cx="18" cy="14.5" r="1.6" fill="#C0765A" />
          </>
        ) : (
          <>
            <circle cx="12" cy="12" r="10" fill="#00618A" />
            <path d="M6 15.5c2-4 4-6 6-6s4 2 6 6" fill="none" stroke="#F29111" strokeWidth="2.2" strokeLinecap="round" />
            <circle cx="12" cy="8" r="1.6" fill="#F29111" />
          </>
        )}
      </svg>
      <span className="engine-label">{isMaria ? 'MariaDB' : 'MySQL'}</span>
    </span>
  );
}

/* Raggruppa le repliche per sorgente: ogni sorgente diventa un insieme con i suoi slave. */
function groupReplicas(nodi) {
  const repliche = nodi.filter((n) => n.sorgente);
  const sorgenti = [...new Set(repliche.map((n) => n.sorgente))];
  const sets = sorgenti.map((src) => ({
    sorgente: src,
    master: nodi.find((n) => n.host === src || n.name === src) || null,
    repliche: repliche.filter((n) => n.sorgente === src),
  }));
  const usati = new Set();
  sets.forEach((g) => {
    g.repliche.forEach((r) => usati.add(r.id));
    if (g.master) usati.add(g.master.id);
  });
  return { sets, resto: nodi.filter((n) => !usati.has(n.id)) };
}

function NodeCard({ node, onOpen, selected, role }) {
  const ghost = node.kind === 'non-in-anagrafica';
  const state = node.up === false ? 'down' : node.up === null || node.up === undefined ? 'wait' : 'up';
  return (
    <button
      type="button"
      className={`node ${state} ${ghost ? 'ghost' : ''} ${selected ? 'selected' : ''} ${role === 'source' ? 'source' : ''}`}
      onClick={() => node.id && onOpen(node)}
      disabled={!node.id}
    >
      <div className="node-head">
        <span className="node-title"><EngineIcon engine={node.motore} /><strong>{node.name}</strong>
          {role === 'source' && <span className="role-tag source">sorgente</span>}
          {role === 'replica' && <span className="role-tag replica">replica</span>}
        </span>
        {state === 'down' && <span className="badge bad">GIÙ</span>}
        {state === 'wait' && <span className="badge neutral">in attesa</span>}
        {state === 'up' && <Badge value={node.statoMembro || 'ONLINE'} />}
      </div>
      <div className="muted">{node.host}:{node.port}</div>
      <div className="node-meta">
        <span>Ruolo: <Badge value={node.ruolo || node.tipo || '—'} /></span>
        {node.versione && <span>Versione: {node.versione}</span>}
        {node.edizione && <span>{node.edizione}</span>}
      </div>
      {state === 'up' && (
        <div className="node-meta muted">
          <span>CPU: {node.cpuPercent != null ? `${node.cpuPercent}%` : '—'}</span>
          <span>Memoria: {node.memUsedBytes != null ? formatBytes(node.memUsedBytes) : '—'}</span>
          <span>Relay log: {formatBytes(node.relayLogBytes)}</span>
          <span>Query attive: {fmt(node.queryAttive)}</span>
        </div>
      )}
      {state === 'down' && <div className="bad-text small">{node.errore}</div>}
      {ghost && <div className="muted small">Membro del cluster non registrato in anagrafica</div>}
    </button>
  );
}

function ClusterCard({ group, onOpen, selectedId }) {
  const stato = group.stato || 'sconosciuto';
  return (
    <section className="set cluster-set">
      <div className="set-head">
        <div>
          <div className="set-title"><h3>Cluster {group.nome}</h3><span className="set-kind">{group.galera ? 'Galera Cluster' : 'InnoDB Cluster'}</span></div>
          <span className="muted">{group.galera ? 'multi-primario · tutti i nodi scrivono' : `primario ${fmt(group.primario)}`}</span>
        </div>
        <div className="cluster-stat">
          <Badge value={stato} />
          <strong>{group.onlineNodi ?? 0}/{group.nodi?.length ?? 0} online</strong>
        </div>
      </div>
      <div className="topo-wrap">
        <ClusterTopology group={group} onOpen={onOpen} selectedId={selectedId} />
      </div>
    </section>
  );
}

/* Indicazione sotto la tabella delle query: solo per chi può aprire il piano. */
function PianoHint() {
  const permessi = useContext(PermessiContext);
  return (
    <p className="muted small">
      Ordinate per tempo di esecuzione, poi per righe esaminate.
      {permessi.includes('diagnostica') ? ' Clicca una query per il piano.' : ' Il piano di esecuzione richiede il profilo operatore.'}
    </p>
  );
}

/* Query attive ordinate per consumo; il clic apre il piano di esecuzione. */
/* Il piano di esecuzione richiede il permesso diagnostica: senza, la tabella non è cliccabile. */
function QueryTable({ rows, onPick, showServer = false }) {
  const permessi = useContext(PermessiContext);
  const apriPiano = permessi.includes('diagnostica') ? onPick : undefined;
  if (!rows || rows.length === 0) return <p className="muted">Nessuna query attiva</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {showServer && <th>Server</th>}
            <th>Schema</th><th>Utente</th><th>Stato</th><th>Secondi</th>
            <th>Righe esaminate</th><th>Righe inviate</th><th>Tmp su disco</th><th>Senza indice</th><th>Query</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((q, i) => (
            <tr key={i} className={apriPiano ? 'clickable' : ''} onClick={() => apriPiano && apriPiano(q)}>
              {showServer && <td>{q.server}</td>}
              <td>{fmt(q.DB)}</td>
              <td>{fmt(q.UTENTE)}</td>
              <td>{fmt(q.STATO)}</td>
              <td>{fmt(q.SECONDI)}</td>
              <td>{fmt(q.RIGHE_ESAMINATE)}</td>
              <td>{fmt(q.RIGHE_INVIATE)}</td>
              <td>{fmt(q.TMP_DISCO)}</td>
              <td>{q.SENZA_INDICE ? 'Sì' : 'No'}</td>
              <td><code className="sql-cell">{fmt(q.QUERY)}</code></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* Icona per copiare un testo negli appunti, con feedback visivo. */
function CopyButton({ text, title }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // ambiente senza Clipboard API: copia tramite area di testo nascosta
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      ta.remove();
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  return (
    <button type="button" className="icon-btn" title={copied ? 'Copiato!' : title} aria-label={title} onClick={copy}>
      {copied ? (
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path d="M20 6 9 17l-5-5" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" /></svg>
      ) : (
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><rect x="9" y="9" width="11" height="11" rx="2" fill="none" stroke="currentColor" strokeWidth="2" /><path d="M5 15V5a2 2 0 0 1 2-2h10" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>
      )}
    </button>
  );
}

/* Piano di esecuzione da EXPLAIN FORMAT=JSON: estrae i passi (tabelle, ordinamenti, raggruppamenti) in ordine. */
function parsePlan(json) {
  let radice;
  try { radice = JSON.parse(json); } catch { return null; }
  const passi = [];
  const visita = (nodo) => {
    if (Array.isArray(nodo)) { nodo.forEach(visita); return; }
    if (!nodo || typeof nodo !== 'object') return;
    for (const [k, v] of Object.entries(nodo)) {
      if (k === 'table' && v && typeof v === 'object') {
        passi.push({ tipo: 'tabella', ...v });
      } else if (k === 'ordering_operation' && v && typeof v === 'object') {
        passi.push({ tipo: 'ordinamento', filesort: !!v.using_filesort });
        visita(v);
      } else if (k === 'grouping_operation' && v && typeof v === 'object') {
        passi.push({ tipo: 'raggruppamento', temp: !!v.using_temporary_table, filesort: !!v.using_filesort });
        visita(v);
      } else if (k === 'duplicates_removal' && v && typeof v === 'object') {
        passi.push({ tipo: 'distinct' });
        visita(v);
      } else if (typeof v === 'object') {
        visita(v);
      }
    }
  };
  visita(radice);
  const costo = Number(radice?.query_block?.cost_info?.query_cost ?? NaN);
  return { passi, costo: Number.isFinite(costo) ? costo : null };
}

/* Icone degli operatori del piano: lente per la ricerca su indice, scansione, ingranaggio per l'ordinamento su disco. */
function IconaPiano({ nome }) {
  const p = { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2,
    strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true, focusable: false };
  switch (nome) {
    case 'seek': // lente d'ingrandimento: ricerca puntuale su indice
      return <svg {...p}><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4.2-4.2" /></svg>;
    case 'range': // lente con intervallo: ricerca su intervallo di chiavi
      return <svg {...p}><circle cx="10" cy="10" r="6" /><path d="m19 19-3.9-3.9" /><path d="M7 10h6" strokeDasharray="2 2" /></svg>;
    case 'scan': // scansione delle voci dell'indice
      return <svg {...p}><path d="M4 6h16M4 12h16M4 18h16" /><path d="M20 3v18" strokeDasharray="2 3" /></svg>;
    case 'fullscan': // scansione completa della tabella
      return <svg {...p}><ellipse cx="12" cy="5" rx="8" ry="3" /><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5" /><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" /></svg>;
    case 'sort': // ordinamento su disco (filesort): ingranaggio con frecce
      return <svg {...p}><circle cx="12" cy="12" r="3" /><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1" /><circle cx="12" cy="12" r="7" strokeDasharray="3 3" /></svg>;
    case 'order': // ordine già fornito dall'indice: frecce ordinate
      return <svg {...p}><path d="M7 4v16M3 16l4 4 4-4" /><path d="M14 7h7M14 12h5M14 17h3" /></svg>;
    case 'group': // raggruppamento: livelli sovrapposti
      return <svg {...p}><path d="m12 3 9 5-9 5-9-5 9-5Z" /><path d="m3 13 9 5 9-5" /></svg>;
    case 'distinct': // eliminazione duplicati: imbuto
      return <svg {...p}><path d="M3 5h18l-7 8v6l-4-2v-4L3 5Z" /></svg>;
    default:
      return <svg {...p}><circle cx="12" cy="12" r="8" /></svg>;
  }
}

/* Descrizione di ogni passo: icona, titolo, colore (ok / warn / bad / neutral). */
function descriviPasso(p) {
  if (p.tipo === 'ordinamento') {
    return p.filesort
      ? { icona: 'sort', titolo: 'Sort', sotto: 'ordinamento su disco (filesort)', colore: 'bad' }
      : { icona: 'order', titolo: 'Order', sotto: 'ordine fornito dall\'indice', colore: 'ok' };
  }
  if (p.tipo === 'raggruppamento') {
    return { icona: 'group', titolo: 'Group', sotto: p.temp ? 'con tabella temporanea' : 'raggruppamento', colore: p.temp || p.filesort ? 'warn' : 'neutral' };
  }
  if (p.tipo === 'distinct') return { icona: 'distinct', titolo: 'Distinct', sotto: 'eliminazione duplicati', colore: 'neutral' };
  const a = p.access_type;
  if (a === 'ALL') return { icona: 'fullscan', titolo: 'Table scan', sotto: 'scansione completa della tabella', colore: 'bad' };
  if (a === 'index') return { icona: 'scan', titolo: 'Index scan', sotto: 'scansione di tutto l\'indice', colore: 'warn' };
  if (a === 'range') return { icona: 'range', titolo: 'Index range seek', sotto: 'ricerca su intervallo di chiavi', colore: 'warn' };
  if (a === 'index_merge') return { icona: 'seek', titolo: 'Index merge', sotto: 'unione di più indici', colore: 'warn' };
  if (a === 'system' || a === 'const') return { icona: 'seek', titolo: 'Const lookup', sotto: 'riga unica', colore: 'ok' };
  if (a === 'eq_ref') return { icona: 'seek', titolo: 'Index seek (unique)', sotto: 'chiave unica', colore: 'ok' };
  if (a === 'ref' || a === 'ref_or_null') return { icona: 'seek', titolo: 'Index seek', sotto: 'ricerca puntuale su indice', colore: 'ok' };
  return { icona: 'seek', titolo: a || 'Accesso', sotto: 'n/d', colore: 'neutral' };
}

/* Costo di un passo: per le tabelle è lettura più valutazione; per gli operatori non è stimato. */
function costoPasso(p) {
  if (p.tipo !== 'tabella') return null;
  const lettura = Number(p.cost_info?.read_cost);
  const valutazione = Number(p.cost_info?.eval_cost);
  if (Number.isFinite(lettura) || Number.isFinite(valutazione)) {
    return (Number.isFinite(lettura) ? lettura : 0) + (Number.isFinite(valutazione) ? valutazione : 0);
  }
  const prefisso = Number(p.cost_info?.prefix_cost);
  return Number.isFinite(prefisso) ? prefisso : null;
}

/* Vista del piano: grafico del costo per passo, passi con icone, avvisi. */
function PlanVisual({ json }) {
  const piano = parsePlan(json);
  if (!piano || piano.passi.length === 0) return <p className="muted">Piano non interpretabile: vedi i dettagli tecnici.</p>;
  const passi = piano.passi.map((p) => ({ p, d: descriviPasso(p), costo: costoPasso(p) }));
  const costiTabella = passi.filter((x) => x.costo !== null);
  const totaleCosti = costiTabella.reduce((s, x) => s + x.costo, 0);
  const totale = piano.costo ?? (totaleCosti || null);
  const scansioni = passi.filter((x) => x.p.tipo === 'tabella' && x.p.access_type === 'ALL');
  const righeMax = Math.max(1, ...passi.filter((x) => x.p.tipo === 'tabella').map((x) => Number(x.p.rows_examined_per_scan) || 0));

  return (
    <div className="plan">
      <div className="plan-summary">
        {totale !== null && <span className="plan-chip">costo totale <strong>{Number(totale).toFixed(2)}</strong></span>}
        <span className="plan-chip">{passi.length} passi</span>
        {scansioni.length > 0
          ? <span className="plan-chip bad">{scansioni.length} scansione{scansioni.length > 1 ? 'i' : ''} completa{scansioni.length > 1 ? 'e' : ''}</span>
          : <span className="plan-chip ok">nessuna scansione completa</span>}
      </div>

      {costiTabella.length > 0 && totaleCosti > 0 && (
        <figure className="plan-chart" aria-label="Ripartizione del costo per passo">
          <figcaption>Ripartizione del costo per tabella</figcaption>
          <div className="plan-stack">
            {costiTabella.map((x, i) => (
              <span key={i} className={`plan-seg ${x.d.colore}`} style={{ width: `${(x.costo / totaleCosti) * 100}%` }}
                title={`${x.p.table_name}: ${x.costo.toFixed(2)} (${((x.costo / totaleCosti) * 100).toFixed(0)}%)`} />
            ))}
          </div>
          <ul className="plan-legend">
            {costiTabella.map((x, i) => (
              <li key={i}>
                <span className={`plan-dot ${x.d.colore}`} />
                <span>{x.p.table_name || '(derivata)'}</span>
                <strong>{x.costo.toFixed(2)}</strong>
                <span className="muted">{((x.costo / totaleCosti) * 100).toFixed(0)}%</span>
              </li>
            ))}
          </ul>
        </figure>
      )}

      {scansioni.map((x, i) => (
        <div key={`avviso-${i}`} className="plan-warning">
          <strong>Scansione completa su {x.p.table_name}</strong>
          {x.p.possible_keys?.length ? ` · indici possibili non usati: ${x.p.possible_keys.join(', ')}` : ' · nessun indice utilizzabile: valutare un indice sulle colonne del filtro'}
        </div>
      ))}

      <ol className="plan-flow">
        {passi.map(({ p, d, costo }, i) => {
          const righe = Number(p.rows_examined_per_scan) || 0;
          const extra = [];
          if (p.tipo === 'tabella') {
            if (p.key) extra.push(['info', `indice ${p.key}`]);
            if (p.using_index) extra.push(['ok', 'solo indice (covering)']);
            if (p.using_join_buffer) extra.push(['warn', 'join buffer']);
            if (p.using_temporary_table) extra.push(['warn', 'tabella temporanea']);
            if (p.using_filesort) extra.push(['warn', 'filesort']);
            if (p.attached_condition) extra.push(['info', 'filtro applicato']);
          }
          return (
            <li key={i} className={`plan-step ${d.colore}`}>
              <div className={`plan-icon ${d.colore}`}><IconaPiano nome={d.icona} /></div>
              <div className="plan-body">
                <div className="plan-head">
                  <span className="plan-title">{d.titolo}{p.tipo === 'tabella' && p.table_name && <> · <strong>{p.table_name}</strong></>}</span>
                  {costo !== null && <span className={`plan-cost ${d.colore}`}>costo {costo.toFixed(2)}</span>}
                </div>
                <div className="plan-sub muted small">{d.sotto}</div>
                {p.tipo === 'tabella' && (
                  <>
                    <div className="plan-meta">
                      <span>righe stimate <strong>{righe.toLocaleString('it-IT')}</strong></span>
                    </div>
                    <div className="plan-bar" aria-hidden="true"><span style={{ width: `${Math.max(2, (righe / righeMax) * 100)}%` }} /></div>
                  </>
                )}
                {extra.length > 0 && <div className="plan-tags">{extra.map(([l, t], j) => <span key={j} className={`plan-tag ${l}`}>{t}</span>)}</div>}
                {p.attached_condition && <code className="plan-cond">{p.attached_condition}</code>}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/* Piano di esecuzione (EXPLAIN) della query selezionata. */
/* Passi di un piano (PostgreSQL, SQL Server): tabella con l'indentazione per livello di annidamento. */
function PianoPassi({ passi }) {
  if (!passi || passi.length === 0) return <p className="muted">Nessun passo del piano.</p>;
  return (
    <div className="table-wrap piano-passi">
      <table>
        <thead>
          <tr><th>Operatore</th><th>Oggetto</th><th>Logico</th><th>Righe stimate</th><th>Costo</th><th>Condizione</th></tr>
        </thead>
        <tbody>
          {passi.map((p, i) => (
            <tr key={i}>
              <td style={{ paddingLeft: 12 + p.profondita * 18 }}>
                {p.profondita > 0 && <span className="piano-freccia">↳ </span>}<strong>{p.operatore || '—'}</strong>
              </td>
              <td>{p.oggetto || '—'}</td>
              <td>{p.logico || '—'}</td>
              <td className="num">{Math.round(Number(p.righeStimate) || 0).toLocaleString('it-IT')}</td>
              <td className="num">{(Number(p.costo) || 0).toFixed(2)}</td>
              <td><code className="sql-cell">{p.dettaglio || ''}</code></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ExplainPanel({ plan, onClose }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let alive = true;
    setData(null); setError(null);
    api(`/servers/${plan.serverId}/explain`, { method: 'POST', body: { sql: plan.sql, schema: plan.schema || '' } })
      .then((r) => alive && setData(r))
      .catch((e) => alive && setError(e.message));
    return () => { alive = false; };
  }, [plan]);

  useEscape(onClose);

  // Finestra sopra la pagina: il piano è visibile qualunque sia la posizione di scorrimento
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal plan-modal" role="dialog" aria-modal="true" aria-label="Piano di esecuzione" onMouseDown={(e) => e.stopPropagation()}>
        <div className="plan-modal-head">
          <h3>Piano di esecuzione — {plan.server}</h3>
          <button className="secondary" onClick={onClose}>Chiudi</button>
        </div>
      <p className="muted">
        Schema: {fmt(plan.schema)} · la query non viene eseguita, viene solo analizzato il piano (EXPLAIN).
      </p>
      <div className="sql-box">
        <pre className="sql-full">{plan.sql}</pre>
        <CopyButton text={plan.sql} title="Copia la query" />
      </div>
      {error && <p className="bad-text">{error}</p>}
      {!data && !error && <p className="muted">Calcolo del piano…</p>}
      {data && (
        <>
          {data.note && <p className="muted small">{data.note}</p>}
          {data.json ? <PlanVisual json={data.json} /> : <PianoPassi passi={data.passi} />}
          <details className="plan-details">
            <summary>Dettagli tecnici</summary>
            {data.json && <Table rows={data.tabella} columns={[
              { key: 'id', label: 'id' }, { key: 'select_type', label: 'select_type' }, { key: 'table', label: 'tabella' },
              { key: 'type', label: 'tipo accesso' }, { key: 'possible_keys', label: 'possible_keys' },
              { key: 'key', label: 'indice usato' }, { key: 'key_len', label: 'key_len' }, { key: 'ref', label: 'ref' },
              { key: 'rows', label: 'righe stimate' }, { key: 'filtered', label: 'filtrato %' }, { key: 'Extra', label: 'Extra' },
            ]} />}
            {data.testo && <pre className="plan-xml">{data.testo}</pre>}
            {data.xml && <pre className="plan-xml">{data.xml}</pre>}
            {data.json && <pre className="plan-json">{data.json}</pre>}
          </details>
        </>
      )}
      </div>
    </div>
  );
}

function EventsPanel({ events }) {
  return (
    <Section title="Eventi rilevati">
      <Table rows={events} columns={[
        { key: 'occurred_at', label: 'Quando', render: (v) => fmtData(v) },
        { key: 'severity', label: 'Gravità', render: (v) => <Badge value={v} /> },
        { key: 'server_name', label: 'Server' },
        { key: 'cluster_name', label: 'Cluster' },
        { key: 'category', label: 'Categoria' },
        { key: 'message', label: 'Messaggio' },
      ]} empty="Nessun evento: il monitoraggio non ha ancora rilevato variazioni" />
    </Section>
  );
}

/* Riepilogo in cima alla dashboard: contatori per stato ed elenco dei problemi, ordinati per gravità. */
function DashboardSummary({ data, onOpen, parte = 'tutto' }) {
  const tutti = [
    ...data.cluster.flatMap((g) => [...g.nodi, ...(g.endpoint || [])]),
    ...data.singoli,
    ...(data.monitor || []),
  ];
  const valutati = tutti.map((n) => ({ n, st: statusOf(n) }));
  const conta = (lvl) => valutati.filter((x) => x.st.level === lvl).length;
  const problemi = valutati
    .filter((x) => x.st.level === 'red' || x.st.level === 'yellow')
    .sort((a, b) => RANK[b.st.level] - RANK[a.st.level]);
  return (
    <section className={parte === 'kpi' ? 'summary-strip' : 'card summary'} aria-label="Riepilogo dello stato">
      {parte !== 'problemi' && <div className="kpis summary-kpis">
        <div className="kpi"><span className="kpi-label">Server</span><strong>{tutti.length}</strong></div>
        <div className="kpi info"><span className="kpi-label">OK</span><strong>{conta('green')}</strong></div>
        <div className="kpi warn"><span className="kpi-label">Attenzione</span><strong>{conta('yellow')}</strong></div>
        <div className="kpi crit"><span className="kpi-label">Critici</span><strong>{conta('red')}</strong></div>
        <div className="kpi"><span className="kpi-label">In attesa</span><strong>{conta('grey')}</strong></div>
      </div>}
      {parte !== 'kpi' && (problemi.length === 0 ? (
        <p className="ok-text">Nessun problema rilevato.</p>
      ) : (
        <>
          <h4>Da controllare ({problemi.length})</h4>
          <ul className="problem-list">
            {problemi.map(({ n, st }, i) => {
              const rosso = st.level === 'red';
              const contenuto = (
                <>
                  <span className={`problem-level ${rosso ? 'crit' : 'warn'}`}>{rosso ? 'CRITICO' : 'ATTENZIONE'}</span>
                  <strong>{n.name}</strong>
                  <span className="muted small">{st.reasons.join(' · ')}</span>
                </>
              );
              return (
                <li key={`${n.id || n.name}-${i}`} className={`problem ${st.level}`}>
                  {n.id ? (
                    <button type="button" className="problem-btn" onClick={() => onOpen(n)}>{contenuto}</button>
                  ) : (
                    <div className="problem-btn static">{contenuto}</div>
                  )}
                </li>
              );
            })}
          </ul>
        </>
      ))}
    </section>
  );
}

/* ------------------------------------------- VISTA AVANZATA: war room a esagoni su piano obliquo */

const WR_R = 46;                       // raggio dell'esagono di base nel piano
const WR_SQ3 = Math.sqrt(3);
const WR_COLORI = { ok: '#2ee6a6', warn: '#ffb020', bad: '#ff3d5a', unknown: '#7d8aa5' };
const WR_ETICHETTE = { ok: 'Operativo', warn: 'Attenzione', bad: 'Critico', unknown: 'Non rilevato' };
const WR_CLUSTER = { ONLINE: 'ok', DEGRADED: 'warn', 'SENZA QUORUM': 'warn', OFFLINE: 'bad' };

// Coordinate assiali di un esagono a punta in alto -> posizione nel piano
const wrPiano = (q, r) => ({ x: WR_R * WR_SQ3 * (q + r / 2), y: WR_R * 1.5 * r });
// Piano inclinato verso chi guarda (quasi perpendicolare allo schermo); le verticali restano verticali
const WR_PROIEZIONE = { a: 0.98, b: -0.3, c: 0.26, d: 0.9 };
const wrProietta = (p) => ({ x: WR_PROIEZIONE.a * p.x + WR_PROIEZIONE.b * p.y, y: WR_PROIEZIONE.c * p.x + WR_PROIEZIONE.d * p.y });
// Inversa della proiezione: dal punto sullo schermo al punto del piano
const wrInversa = (s) => {
  const { a, b, c, d } = WR_PROIEZIONE;
  const det = a * d - b * c;
  return { x: (d * s.x - b * s.y) / det, y: (-c * s.x + a * s.y) / det };
};
// Quanto raggio serve all'esagono (a punta in alto, centrato in cx,cy) per contenere il punto
const wrRaggioNecessario = (p, cx, cy) => {
  const dx = Math.abs(p.x - cx), dy = Math.abs(p.y - cy);
  return Math.max(dx / 0.866, (0.5 * dx + 0.866 * dy) / 0.866);
};
const wrEsagono = (cx, cy, raggio) => [0, 1, 2, 3, 4, 5].map((k) => {
  const a = ((30 + 60 * k) * Math.PI) / 180;
  return { x: cx + raggio * Math.cos(a), y: cy + raggio * Math.sin(a) };
});
const wrPunti = (pts) => pts.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');

// Celle di un'isola: il primo nodo al centro, gli altri sugli anelli attorno
function wrCelle(n) {
  if (n === 1) return [[0, 0]];
  const out = [];
  for (let d = 1; out.length < n; d++) {
    for (let dq = -d; dq <= d; dq++) {
      for (let dr = -d; dr <= d; dr++) {
        const ds = -dq - dr;
        if (Math.max(Math.abs(dq), Math.abs(dr), Math.abs(ds)) === d) out.push([dq, dr]);
      }
    }
  }
  return out.slice(0, n);
}

function wrStato(n) {
  if (n.up === null || n.up === undefined || n.kind === 'non-in-anagrafica') return 'unknown';
  if (n.up === false) return 'bad';
  const m = n.statoMembro;
  if (m && m !== 'ONLINE' && m !== 'Synced') return ['ERROR', 'OFFLINE', 'UNREACHABLE'].includes(m) ? 'bad' : 'warn';
  if (n.replicaOk === false) return 'warn';
  return 'ok';
}

// Colore del legame di replica: stato della replica e ritardo
const wrColoreReplica = (n) => {
  if (n.replicaOk === false) return 'bad';
  if (Number(n.ritardoSec) > 30) return 'warn';
  return n.replicaOk === true ? 'ok' : 'unknown';
};

// Costruisce la scena: posizioni, prismi, piano, griglia e legami (cluster e replica)
function wrScena(data) {
  const isole = [
    ...data.cluster.map((g) => ({ gruppo: g, nodi: g.nodi || [] })),
    ...data.singoli.map((s) => ({ gruppo: null, nodi: [s] })),
  ].filter((i) => i.nodi.length > 0);
  const cols = Math.max(1, Math.ceil(Math.sqrt(isole.length)));
  const nodi = [];
  isole.forEach((isola, i) => {
    const q0 = (i % cols) * 7 + (Math.floor(i / cols) % 2) * 3;
    const r0 = Math.floor(i / cols) * 6;
    // I membri di un cluster si distanziano di due celle, così le etichette restano leggibili
    const scala = isola.gruppo && isola.nodi.length > 1 ? 2 : 1;
    wrCelle(isola.nodi.length).forEach(([dq, dr], k) => {
      const n = isola.nodi[k];
      const piano = wrPiano(q0 + dq * scala, r0 + dr * scala);
      const centro = wrProietta(piano);
      const stato = wrStato(n);
      // Altezza del prisma: carico CPU (più alto = più carico)
      const H = 28 + Math.min(100, Number(n.cpuPercent) || 0) * 0.6;
      const base = wrEsagono(centro.x, centro.y, WR_R * 0.66);
      const top = base.map((p) => ({ x: p.x, y: p.y - H }));
      // Facce laterali visibili: quelle rivolte verso chi guarda (lato basso dello schermo)
      const lati = [];
      for (let k = 0; k < 6; k++) {
        const a = base[k], b = base[(k + 1) % 6];
        if ((a.y + b.y) / 2 > centro.y) lati.push([a, b, top[(k + 1) % 6], top[k]]);
      }
      nodi.push({ n, gruppo: isola.gruppo, piano, centro, stato, H, base, top, lati,
        tetto: { x: centro.x, y: centro.y - H } });
    });
  });

  // Piano esagonale che contiene tutte le isole
  // Il piano deve contenere i prismi e anche il corpo del drone mentre sorvola ogni host.
  // La scala del drone dipende dalla dimensione della scena: si ripete il calcolo finché è stabile.
  const puntiPiano = nodi.flatMap((x) => [...x.base, ...x.top].map(wrInversa));
  const centriPiano = nodi.map((x) => x.piano);
  let kDrone = 1.2;
  let piano = null;
  for (let giro = 0; giro < 6; giro++) {
    const vQuota = wrInversa({ x: 0, y: -130 * kDrone });
    const vLato = wrInversa({ x: 40 * kDrone, y: 0 });
    const vAlto = wrInversa({ x: 0, y: -150 * kDrone });
    const puntiVolo = centriPiano.flatMap((p) => [
      p,
      { x: p.x + vAlto.x, y: p.y + vAlto.y },
      { x: p.x + vQuota.x, y: p.y + vQuota.y },
      { x: p.x + vQuota.x + vLato.x, y: p.y + vQuota.y + vLato.y },
      { x: p.x + vQuota.x - vLato.x, y: p.y + vQuota.y - vLato.y },
    ]);
    const insieme = [...puntiPiano, ...puntiVolo];
    const cx = (Math.min(...insieme.map((p) => p.x)) + Math.max(...insieme.map((p) => p.x))) / 2;
    const cy = (Math.min(...insieme.map((p) => p.y)) + Math.max(...insieme.map((p) => p.y))) / 2;
    const raggioPiano = Math.max(...insieme.map((p) => wrRaggioNecessario(p, cx, cy))) + WR_R * 0.7;
    const piatto = wrEsagono(cx, cy, raggioPiano).map(wrProietta);
    const fondo = piatto.map((p) => ({ x: p.x, y: p.y + 22 }));
    const limiti = [...piatto, ...fondo, ...nodi.map((x) => x.tetto), ...nodi.flatMap((x) => x.base)];
    const minX = Math.min(...limiti.map((p) => p.x)), maxX = Math.max(...limiti.map((p) => p.x));
    const minY = Math.min(...limiti.map((p) => p.y)) - 40, maxY = Math.max(...limiti.map((p) => p.y));
    const larghezza = maxX - minX + 120;
    // Dimensione del testo proporzionale alla scena, così resta leggibile a schermo
    const fs = Math.min(40, Math.max(11, larghezza / 95));
    piano = { cx, cy, raggioPiano, piatto, fondo, minX, minY, maxY, larghezza, fs };
    const kNuovo = Math.max(1.2, fs / 9);
    if (kNuovo <= kDrone + 1e-9) break;
    kDrone = kNuovo;
  }
  const { cx, cy, raggioPiano, piatto, fondo, minX, minY, maxY, larghezza, fs } = piano;
  const latiPiatto = [];
  for (let k = 0; k < 6; k++) {
    const a = piatto[k], b = piatto[(k + 1) % 6];
    if ((a.y + b.y) / 2 > wrProietta({ x: cx, y: cy }).y) latiPiatto.push([a, b, fondo[(k + 1) % 6], fondo[k]]);
  }

  // Griglia esagonale sul piano (solo celle dentro il piano)
  const griglia = [];
  for (let q = -4; q <= 60; q++) {
    for (let r = -4; r <= 50; r++) {
      const p = wrPiano(q, r);
      if (wrRaggioNecessario(p, cx, cy) < raggioPiano - WR_R * 0.6) {
        griglia.push(wrEsagono(p.x, p.y, WR_R).map(wrProietta));
      }
    }
  }

  // Spazio sopra i nodi per il teschio e l'etichetta
  const viewBox = `${minX - 60} ${minY - 60 - fs * 3} ${larghezza} ${maxY - minY + 120 + fs * 3}`;

  // Legami
  const legami = [];
  const perGruppo = new Map();
  nodi.forEach((x) => { if (x.gruppo) perGruppo.set(x.gruppo, [...(perGruppo.get(x.gruppo) || []), x]); });
  const arco = (a, b) => {
    const dist = Math.hypot(b.x - a.x, b.y - a.y);
    const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2 - (dist * 0.22 + 26);
    return `M${a.x.toFixed(1)},${a.y.toFixed(1)} Q${mx.toFixed(1)},${my.toFixed(1)} ${b.x.toFixed(1)},${b.y.toFixed(1)}`;
  };
  for (const [g, membri] of perGruppo) {
    const colore = WR_CLUSTER[g.stato] || 'unknown';
    const coppie = [];
    if (membri.length <= 4) {
      for (let i = 0; i < membri.length; i++) for (let j = i + 1; j < membri.length; j++) coppie.push([membri[i], membri[j]]);
    } else {
      membri.forEach((x, i) => coppie.push([x, membri[(i + 1) % membri.length]]));
    }
    coppie.forEach(([a, b]) => legami.push({ d: arco(a.tetto, b.tetto), colore, flusso: false, chiave: `c-${a.n.id}-${b.n.id}` }));
  }
  const perHost = new Map();
  nodi.forEach((x) => {
    [x.n.host, x.n.name].forEach((h) => { if (h) perHost.set(String(h).toLowerCase(), x); });
  });
  nodi.forEach((x) => {
    const sorg = x.n.sorgente ? String(x.n.sorgente).split(':')[0].toLowerCase() : '';
    const fonte = sorg && perHost.get(sorg);
    if (fonte && fonte !== x) {
      legami.push({ d: arco(fonte.tetto, x.tetto), colore: wrColoreReplica(x.n), flusso: true, chiave: `r-${x.n.id}` });
    }
  });

  return { nodi, piatto, fondo, latiPiatto, griglia, legami, viewBox, fs, cx, cy, raggioPiano, kDrone };
}

const WR_SORVOLO_MS = 5 * 60 * 1000;   // un sorvolo ogni 5 minuti

// Percorso del drone: sequenza di host (vicino più prossimo, da sinistra), memorizzata per chiave
function wrChiave(n) {
  return `${n.id ?? ''}|${n.name}`;
}

function wrPercorso(scena) {
  const nodi = scena.nodi;
  if (nodi.length === 0) return [];
  const restanti = [...nodi].sort((a, b) => a.centro.x - b.centro.x);
  const ordine = [restanti.shift()];
  while (restanti.length > 0) {
    const ultimo = ordine[ordine.length - 1].centro;
    let migliore = 0;
    restanti.forEach((x, k) => {
      const d = Math.hypot(x.centro.x - ultimo.x, x.centro.y - ultimo.y);
      const dm = Math.hypot(restanti[migliore].centro.x - ultimo.x, restanti[migliore].centro.y - ultimo.y);
      if (d < dm) migliore = k;
    });
    ordine.push(restanti.splice(migliore, 1)[0]);
  }
  return ordine.map((x) => wrChiave(x.n));
}

// Posizione al suolo del drone al tempo t (0..1), sulla scena attuale: i nodi che cambiano restano seguiti
function wrPunto(scena, chiavi, t) {
  const punti = chiavi
    .map((c) => scena.nodi.find((x) => wrChiave(x.n) === c))
    .filter(Boolean)
    .map((x) => x.centro);
  if (punti.length === 0) return { x: 0, y: 0 };
  if (punti.length === 1) return punti[0];
  const segmenti = punti.length - 1;
  const pos = Math.min(0.9999, Math.max(0, t)) * segmenti;
  const seg = Math.floor(pos);
  const f = pos - seg;
  const a = punti[seg], b = punti[seg + 1];
  return { x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f };
}

// Drone di controllo: sorvola in sequenza gli host, con ombra e fascio di scansione sul nodo
function WrDrone({ scena, onFine }) {
  const scenaRef = useRef(scena);
  scenaRef.current = scena;
  const [chiavi] = useState(() => wrPercorso(scena));
  // Tempo di volo proporzionale al numero di host, tra 10 e 40 secondi
  const [durata] = useState(() => Math.min(40, Math.max(10, chiavi.length * 2.5)));
  const [k] = useState(() => scena.kDrone);
  const [posizione, setPosizione] = useState(() => wrPunto(scena, chiavi, 0));

  useEffect(() => {
    let frame = 0;
    const inizio = performance.now();
    const passo = (ora) => {
      const t = (ora - inizio) / (durata * 1000);
      setPosizione(wrPunto(scenaRef.current, chiavi, t));
      if (t < 1) frame = requestAnimationFrame(passo);
    };
    frame = requestAnimationFrame(passo);
    const fine = setTimeout(onFine, durata * 1000 + 1500);
    return () => { cancelAnimationFrame(frame); clearTimeout(fine); };
  }, [chiavi, durata, onFine]);

  return (
    <g transform={`translate(${posizione.x.toFixed(1)},${posizione.y.toFixed(1)}) scale(${k.toFixed(2)})`}>
      <ellipse cx="0" cy="0" rx="28" ry="9" fill="#000" opacity="0.4" />
      <polygon points="-6,-124 6,-124 36,0 -36,0" fill="url(#wr-raggio)" />
      <g transform="translate(0,-130)">
          <line x1="-18" y1="-14" x2="18" y2="14" stroke="#cfeeff" strokeWidth="3" />
          <line x1="18" y1="-14" x2="-18" y2="14" stroke="#cfeeff" strokeWidth="3" />
          {[[-18, -14], [18, -14], [-18, 14], [18, 14]].map(([x, y]) => (
            <circle key={`${x}${y}`} cx={x} cy={y} r="8" fill="rgba(47,196,255,.3)" stroke="#2fc4ff" strokeWidth="1.5" />
          ))}
          <rect x="-7" y="-5" width="14" height="10" rx="3" fill="#0a2236" stroke="#2fc4ff" strokeWidth="1.5" />
          <circle className="wr-led" cx="0" cy="0" r="2.2" fill="#ff3d5a" />
        </g>
    </g>
  );
}

/* Orologio analogico dell'Overview: si aggiorna ogni secondo. */
function WrOrologio() {
  const [ora, setOra] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setOra(new Date()), 1000);
    return () => clearInterval(t);
  }, []);
  const ms = ora.getMilliseconds();
  const secondi = ora.getSeconds() + ms / 1000;
  const minuti = ora.getMinutes() + secondi / 60;
  const ore = (ora.getHours() % 12) + minuti / 60;
  const gradi = (v) => v * 6;
  const tacche = Array.from({ length: 12 }, (_, i) => i);
  return (
    <svg className="wr-orologio" viewBox="-60 -60 120 120" role="img" aria-label={`Orologio: ${ora.toLocaleTimeString('it-IT')}`}>
      <circle r="56" className="wr-orologio-quadrante" />
      {tacche.map((i) => (
        <line key={i} x1="0" y1="-50" x2="0" y2={i % 3 === 0 ? -40 : -44}
          transform={`rotate(${i * 30})`} className={i % 3 === 0 ? 'wr-orologio-tacca forte' : 'wr-orologio-tacca'} />
      ))}
      <line x1="0" y1="0" x2="0" y2="-26" strokeWidth="4" strokeLinecap="round" className="wr-orologio-lancetta"
        transform={`rotate(${ore * 30})`} />
      <line x1="0" y1="0" x2="0" y2="-36" strokeWidth="3" strokeLinecap="round" className="wr-orologio-lancetta"
        transform={`rotate(${gradi(minuti)})`} />
      <line x1="0" y1="8" x2="0" y2="-42" strokeWidth="1.2" strokeLinecap="round" className="wr-orologio-secondi"
        transform={`rotate(${gradi(secondi)})`} />
      <circle r="3" className="wr-orologio-perno" />
    </svg>
  );
}

function WarRoomView({ data, onOpen, onExit }) {
  const [hover, setHover] = useState(null);
  const [selez, setSelez] = useState(null);
  const [volo, setVolo] = useState({ n: 0, attivo: false });
  const fineVolo = useCallback(() => setVolo((v) => ({ ...v, attivo: false })), []);
  const scena = useMemo(() => wrScena(data), [data]);
  const dettaglio = selez || hover;

  // Sorvoli periodici: un primo passaggio dopo qualche secondo, poi ogni 5 minuti
  useEffect(() => {
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return undefined;
    const avvia = () => setVolo((v) => ({ n: v.n + 1, attivo: true }));
    const primo = setTimeout(avvia, 5000);
    const ciclo = setInterval(avvia, WR_SORVOLO_MS);
    return () => { clearTimeout(primo); clearInterval(ciclo); };
  }, []);

  const conteggi = { ok: 0, warn: 0, bad: 0, unknown: 0 };
  scena.nodi.forEach((x) => { conteggi[x.stato] += 1; });
  const ruolo = (n) => (n.ruolo ? String(n.ruolo) : '');
  return (
    <div className="war-room">
      <div className="wr-hud wr-titolo">
        <div className="wr-brand">DBGUARDIAN <span>// OVERVIEW</span></div>
        <div className="wr-orario">Rilevato alle {new Date(data.generatoAlle).toLocaleTimeString('it-IT')}</div>
        <div className="wr-contatori">
          <span style={{ color: WR_COLORI.ok }}>{conteggi.ok} operativi</span>
          <span style={{ color: WR_COLORI.warn }}>{conteggi.warn} attenzione</span>
          <span style={{ color: WR_COLORI.bad }}>{conteggi.bad} critici</span>
          <span style={{ color: WR_COLORI.unknown }}>{conteggi.unknown} non rilevati</span>
        </div>
      </div>
      <div className="wr-azioni">
        <WrOrologio />
        <button className="secondary" onClick={onExit}>Vista standard</button>
      </div>

      <svg className="wr-svg" viewBox={scena.viewBox} role="img" aria-label="Mappa dei server a esagoni">
        <defs>
          <filter id="wr-glow" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="3" result="sfocato" />
            <feMerge><feMergeNode in="sfocato" /><feMergeNode in="SourceGraphic" /></feMerge>
          </filter>
          <linearGradient id="wr-raggio" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#2fc4ff" stopOpacity="0.55" />
            <stop offset="1" stopColor="#2fc4ff" stopOpacity="0" />
          </linearGradient>
          <linearGradient id="wr-piano" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#14405c" />
            <stop offset="1" stopColor="#081a29" />
          </linearGradient>
        </defs>

        <polygon points={wrPunti(scena.fondo)} fill="#03101a" />
        {scena.latiPiatto.map((q, i) => <polygon key={`lp${i}`} points={wrPunti(q)} fill="#0a2236" />)}
        <polygon points={wrPunti(scena.piatto)} fill="url(#wr-piano)" stroke="#2fc4ff" strokeOpacity="0.7" strokeWidth="2" filter="url(#wr-glow)" />
        {scena.griglia.map((g, i) => (
          <polygon key={`g${i}`} points={wrPunti(g)} fill="none" stroke="#2fc4ff" strokeOpacity="0.14" strokeWidth="1" />
        ))}

        {scena.nodi.map((x) => {
          const colore = WR_COLORI[x.stato];
          const interattivo = Boolean(x.n.id);
          return (
            <g key={x.n.id || `ghost-${x.n.name}-${x.centro.x}`} className={`wr-nodo wr-${x.stato}`}
              tabIndex={interattivo ? 0 : -1} role={interattivo ? 'button' : undefined}
              aria-label={`${x.n.name}: ${WR_ETICHETTE[x.stato]}`}
              onMouseEnter={() => setHover(x.n)} onMouseLeave={() => setHover(null)}
              onFocus={() => setHover(x.n)} onBlur={() => setHover(null)}
              onClick={() => interattivo && setSelez(selez === x.n ? null : x.n)}
              onKeyDown={(e) => { if (interattivo && e.key === 'Enter') setSelez(x.n); }}>
              {x.lati.map((q, k) => <polygon key={k} points={wrPunti(q)} fill={colore} className="wr-lato" />)}
              <polygon points={wrPunti(x.top)} fill={colore} className="wr-tetto" filter="url(#wr-glow)" />
              {x.n.up === false && (
                <g transform={`translate(${x.tetto.x.toFixed(1)},${(x.tetto.y - scena.fs * 2.7).toFixed(1)}) scale(${(scena.fs / 9).toFixed(2)})`}>
                <g className="wr-teschio">
                  <title>Non raggiungibile</title>
                  <path d="M-9,-2 A9,9 0 1 1 9,-2 L9,2 Q9,6 5,6 L5,9 L-5,9 L-5,6 Q-9,6 -9,2 Z" fill="#ff3d5a" stroke="#02070d" strokeWidth="1.2" />
                  <circle cx="-3.6" cy="-1" r="2.8" fill="#02070d" />
                  <circle cx="3.6" cy="-1" r="2.8" fill="#02070d" />
                  <path d="M0,2 L-1.3,5 L1.3,5 Z" fill="#02070d" />
                  <path d="M-3,7 V9 M0,7 V9 M3,7 V9" stroke="#02070d" strokeWidth="0.8" />
                </g>
                </g>
              )}
              <text x={x.tetto.x} y={x.tetto.y - scena.fs * 1.1} className="wr-nome"
                style={{ fontSize: scena.fs, strokeWidth: scena.fs * 0.27 }}>{x.n.name}</text>
              <text x={x.tetto.x} y={x.tetto.y + scena.fs * 0.35} className="wr-ruolo"
                style={{ fontSize: scena.fs * 0.75 }}>{ruolo(x.n).slice(0, 14)}</text>
            </g>
          );
        })}

        {volo.attivo && <WrDrone key={volo.n} scena={scena} onFine={fineVolo} />}

        {scena.legami.map((l) => (
          <path key={l.chiave} d={l.d} fill="none" stroke={WR_COLORI[l.colore]}
            strokeWidth={l.flusso ? 2.2 : 1.6} strokeOpacity={0.9} filter="url(#wr-glow)"
            className={l.flusso ? 'wr-filo wr-flusso' : 'wr-filo'} />
        ))}
      </svg>

      <div className="wr-hud wr-legenda">
        {Object.keys(WR_COLORI).map((k) => (
          <span key={k}><i style={{ background: WR_COLORI[k] }} />{WR_ETICHETTE[k]}</span>
        ))}
        {volo.attivo && <span className="wr-sorvolo">Sorvolo di controllo in corso</span>}
        <span className="wr-nota">Altezza = carico CPU · filo tratteggiato = replica · filo continuo = cluster</span>
      </div>

      {dettaglio && (
        <div className="wr-hud wr-dettaglio" role="status">
          <div className="wr-dett-titolo">{dettaglio.name}</div>
          <dl>
            <dt>Host</dt><dd>{dettaglio.host ? `${dettaglio.host}${dettaglio.port ? `:${dettaglio.port}` : ''}` : '—'}</dd>
            <dt>Motore</dt><dd>{[dettaglio.motore, dettaglio.versione].filter(Boolean).join(' ') || '—'}</dd>
            <dt>Ruolo</dt><dd>{ruolo(dettaglio) || '—'}</dd>
            <dt>Stato</dt><dd>{dettaglio.up === false ? 'non raggiungibile' : dettaglio.up === null || dettaglio.up === undefined ? 'in attesa' : (dettaglio.statoMembro || 'raggiungibile')}</dd>
            <dt>Sorgente</dt><dd>{dettaglio.sorgente || '—'}</dd>
            <dt>Ritardo</dt><dd>{dettaglio.ritardoSec != null ? `${dettaglio.ritardoSec} s` : '—'}</dd>
            <dt>CPU</dt><dd>{dettaglio.cpuPercent != null ? `${Number(dettaglio.cpuPercent).toFixed(0)} %` : '—'}</dd>
          </dl>
          {dettaglio.id && <button onClick={() => { onExit(); onOpen(dettaglio); }}>Apri scheda</button>}
        </div>
      )}
    </div>
  );
}

function Dashboard() {
  const [data, setData] = useState(null);
  const [selected, setSelected] = useState(null);
  const [plan, setPlan] = useState(null);
  const [detail, setDetail] = useState(null);
  const [report, setReport] = useState(null);
  const [loadingReport, setLoadingReport] = useState(false);
  const [error, setError] = useState(null);
  const [refreshSec, setRefreshSec] = useState(() => Number(localStorage.getItem('refreshSec')) || 60);

  const [ambiente, setAmbiente] = useState(() => localStorage.getItem('ambienteDashboard') || 'tutti');
  const [sito, setSito] = useState(() => localStorage.getItem('sitoDashboard') || 'tutti');
  const [elencoSiti, setElencoSiti] = useState([]);
  useEffect(() => { api('/siti').then(setElencoSiti).catch(() => setElencoSiti([])); }, []);

  const load = useCallback(async () => {
    try {
      const q = `ambiente=${encodeURIComponent(ambiente)}&sito=${encodeURIComponent(sito)}`;
      setData(await api(`/dashboard?${q}`)); setError(null);
    } catch (e) { setError(e.message); }
  }, [ambiente, sito]);

  function changeSito(e) {
    localStorage.setItem('sitoDashboard', e.target.value);
    setSito(e.target.value);
  }

  function changeAmbiente(e) {
    localStorage.setItem('ambienteDashboard', e.target.value);
    setAmbiente(e.target.value);
  }

  useEffect(() => {
    load();
    const t = setInterval(load, refreshSec * 1000);
    return () => clearInterval(t);
  }, [load, refreshSec]);

  const [rilevando, setRilevando] = useState(false);
  const permessi = useContext(PermessiContext);
  const [avanzata, setAvanzata] = useState(() => localStorage.getItem('vistaAvanzata') === '1');
  function cambiaVista(attiva) {
    localStorage.setItem('vistaAvanzata', attiva ? '1' : '0');
    setAvanzata(attiva);
  }

  // Rilevamento completo immediato di tutti i server (pulsante "Rileva ora")
  async function rileva() {
    setRilevando(true);
    try { await api('/poll', { method: 'POST' }); await load(); } catch (e) { setError(e.message); }
    setRilevando(false);
  }

  function changeRefresh(e) {
    const v = Number(e.target.value);
    localStorage.setItem('refreshSec', String(v));
    setRefreshSec(v);
  }

  async function open(node) {
    setSelected(node);
    setReport(null);
    setLoadingReport(true);
    try { setReport(await api(`/servers/${node.id}/report`)); } catch (e) { setError(e.message); }
    setLoadingReport(false);
  }

  if (avanzata && data) {
    return (
      <WarRoomView data={data} onExit={() => cambiaVista(false)}
        onOpen={(n) => { cambiaVista(false); open(n); }} />
    );
  }

  return (
    <>
      <div className="toolbar">
        <span className="muted">
          {data ? `Vista aggiornata alle ${new Date(data.generatoAlle).toLocaleTimeString('it-IT')} · stato dei server ogni 5 minuti, sezioni pesanti ogni 15` : 'Caricamento…'}
        </span>
        <div className="toolbar-actions">
          <button className="secondary" onClick={() => cambiaVista(true)} disabled={!data}>Vista avanzata</button>
          <label className="refresh-select">
            Sito
            <select value={sito} onChange={changeSito} aria-label="Filtra per sito">
              <option value="tutti">Tutti</option>
              {elencoSiti.map((x) => <option key={x.id} value={x.nome}>{x.nome}</option>)}
            </select>
          </label>
          <label className="refresh-select">
            Ambiente
            <select value={ambiente} onChange={changeAmbiente} aria-label="Filtra per ambiente">
              <option value="tutti">Tutti</option>
              <option value="produzione">Produzione</option>
              <option value="certificazione">Certificazione</option>
              <option value="collaudo">Collaudo</option>
              <option value="nessuno">Non definito</option>
            </select>
          </label>
          {permessi.includes('diagnostica') && (
            <button className="secondary" onClick={rileva} disabled={rilevando}>{rilevando ? 'Rilevo…' : 'Rileva ora'}</button>
          )}
        <label className="refresh-select">
          Aggiornamento
          <select value={refreshSec} onChange={changeRefresh}>
            <option value={5}>5 secondi</option>
            <option value={30}>30 secondi</option>
            <option value={60}>1 minuto</option>
            <option value={900}>15 minuti</option>
          </select>
        </label>
        </div>
      </div>
      {error && (
        <div className="bad-text banner" role="alert">
          {error} <button className="secondary" onClick={load}>Riprova</button>
        </div>
      )}

      {data && <DashboardSummary data={data} onOpen={open} parte="kpi" />}


      <div className="dash-split">
        <div className="dash-main">
      {data && data.monitor && data.monitor.length > 0 && (
        <section className="set monitor-set">
          <div className="set-head">
            <div className="set-title"><h3>Database del monitor</h3><span className="set-kind">DBGuardian</span></div>
            <span className="muted">il database che conserva anagrafica, snapshot ed eventi</span>
          </div>
          <div className="tiles">
            {data.monitor.map((n) => (
              <Tile key={n.id} node={n} onOpen={open} selected={selected?.id === n.id} />
            ))}
          </div>
        </section>
      )}

      {data && detail && (
        <DetailView detail={detail} data={data} onClose={() => setDetail(null)} onOpen={open} selectedId={selected?.id} />
      )}

      {data && !detail && (
        <>
          <Legend />
          {data.cluster.map((g) => (
            <ClusterSummary key={g.nome} group={g} onOpen={open} selectedId={selected?.id}
              onDetail={() => setDetail({ tipo: 'cluster', key: g.nome })} />
          ))}
          {(() => {
            const { sets, resto } = groupReplicas(data.singoli);
            return (
              <>
                {sets.map((g) => (
                  <ReplicaSummary key={g.sorgente} set={g} onOpen={open} selectedId={selected?.id}
                    onDetail={() => setDetail({ tipo: 'replica', key: g.sorgente })} />
                ))}
                {resto.length > 0 && <SingleSummary nodes={resto} onOpen={open} selectedId={selected?.id} />}
              </>
            );
          })()}
        </>
      )}

      {data && data.cluster.length === 0 && data.singoli.length === 0 && (
        <p className="muted">Nessun server in anagrafica: aggiungine uno nella scheda Configurazione.</p>
      )}

        </div>
        <aside className="dash-side" aria-label="Avvisi">
          {data && <DashboardSummary data={data} onOpen={open} parte="problemi" />}
          {data && <EventsPanel events={data.eventi} />}
        </aside>
      </div>

      {plan && <ExplainPanel plan={plan} onClose={() => setPlan(null)} />}

      {selected && (
        <div className="report-head">
          <h2>Dettaglio: {selected.name}</h2>
          {report && <span className="muted">rilevato alle {new Date(report.generatoAlle).toLocaleTimeString('it-IT')}</span>}
        </div>
      )}
      {loadingReport && <p className="muted">Raccolta dati in corso…</p>}
      {report && <Report report={report} />}
    </>
  );
}

/* ------------------------------------------------------------------- REPORT */

function Report({ report: r }) {
  const cl = r.cluster;
  const [plan, setPlan] = useState(null);
  return (
    <div className="report">
      {r.connessione && r.connessione.ok === false && (
        <p className="bad-text">Connessione fallita: {r.connessione.errore}</p>
      )}
      {(r.utentiDettaglio || r.utenti != null) && (
        <Section title="Utenti">
          {r.utentiDettaglio && (
            <Table rows={r.utentiDettaglio} columns={[
              { key: 'utente', label: 'Utente' },
              { key: 'tipo', label: 'Tipo' },
              { key: 'creazione', label: 'Creazione', render: (v) => (v && /^\d{4}-/.test(v) ? fmtData(v) : (v || '—')) },
              { key: 'scadenza', label: 'Scadenza' },
            ]} empty="Nessun utente" />
          )}
          {r.utenti != null && (
            <Table rows={r.utenti} columns={[
              { key: 'utente', label: 'Utente@host' }, { key: 'plugin', label: 'Plugin' },
              { key: 'bloccato', label: 'Bloccato' }, { key: 'passwordScaduta', label: 'Password scaduta' },
              { key: 'privilegiGlobali', label: 'Privilegi globali' }, { key: 'database', label: 'Database' },
            ]} empty="Nessun utente" />
          )}
        </Section>
      )}
      {r.databaseDettaglio && (
        <Section title="Database">
          <Table rows={r.databaseDettaglio} columns={[
            { key: 'database', label: 'Database' },
            { key: 'creazione', label: 'Creazione', render: (v) => (v && /^\d{4}-/.test(v) ? fmtData(v) : (v || '—')) },
            { key: 'stato', label: 'Stato' },
            { key: 'dimensioneMB', label: 'MB' },
          ]} empty="Nessun database" />
        </Section>
      )}
      <Section title="Identità e versione">
        <Table rows={[r.identita || {}]} columns={[
          { key: 'edizione', label: 'Edizione' }, { key: 'versione', label: 'Versione' },
          { key: 'patch', label: 'Patch' }, { key: 'hostname', label: 'Hostname' },
          { key: 'porta', label: 'Porta' }, { key: 'readOnly', label: 'Read only' },
          { key: 'superReadOnly', label: 'Super read only' },
        ]} />
        <p>Topologia: <strong>{r.topologia?.tipo}</strong>{r.topologia?.cluster && <> — cluster <strong>{r.topologia.cluster}</strong></>}</p>
      </Section>

      {r.risorse && (
      <Section title="Risorse del servizio MySQL">
        <Table rows={[r.risorse || {}]} columns={[
          { key: 'cpuPercent', label: 'CPU (%)' },
          { key: 'cpuCore', label: 'Core disponibili' },
          { key: 'memUsedBytes', label: 'Memoria usata', render: formatBytes },
          { key: 'memLimitBytes', label: 'Memoria totale', render: formatBytes },
          { key: 'memPercent', label: 'Memoria (%)' },
          { key: 'memoriaMysqldAllocataBytes', label: 'Memoria allocata da mysqld', render: formatBytes },
          { key: 'bufferPoolBytes', label: 'InnoDB buffer pool', render: formatBytes },
        ]} empty="Dati non disponibili" />
      </Section>
      )}

      {cl && (
        <Section title="Stato del cluster">
          <p>
            Stato: <Badge value={cl.stato} /> &nbsp; Membri online: <strong>{cl.online}/{cl.totale}</strong>
            &nbsp; Primario: <strong>{fmt(cl.primario)}</strong>
          </p>
          <Table rows={cl.membri} columns={[
            { key: 'MEMBER_HOST', label: 'Membro' }, { key: 'MEMBER_PORT', label: 'Porta' },
            { key: 'MEMBER_ROLE', label: 'Ruolo', render: (v) => <Badge value={v} /> },
            { key: 'MEMBER_STATE', label: 'Stato', render: (v) => <Badge value={v} /> },
            { key: 'MEMBER_VERSION', label: 'Versione' },
          ]} />
          {cl.aggiornamento && (
            <>
              <h4>Aggiornamento dei nodi</h4>
              <p>
                Più aggiornato: <strong>{fmt(cl.aggiornamento.piuAggiornato)}</strong> &nbsp;|&nbsp;
                Meno aggiornato: <strong>{fmt(cl.aggiornamento.menoAggiornato)}</strong>
                {cl.aggiornamento.differenzaTransazioni != null && <> &nbsp;|&nbsp; differenza: {cl.aggiornamento.differenzaTransazioni} transazioni</>}
              </p>
              <Table rows={cl.aggiornamento.nodi} columns={[
                { key: 'nodo', label: 'Nodo' }, { key: 'raggiungibile', label: 'Raggiungibile' },
                { key: 'transazioni', label: 'Transazioni eseguite' }, { key: 'errore', label: 'Errore' },
              ]} />
            </>
          )}
        </Section>
      )}

      <Section title="Replica e relay log">
        <p>Dimensione totale relay log: <strong>{formatBytes(r.relayLogTotaleBytes)}</strong></p>
        <Table rows={r.replica} columns={[
          { key: 'canale', label: 'Canale' }, { key: 'sorgente', label: 'Sorgente' },
          { key: 'ioAttivo', label: 'IO thread' }, { key: 'sqlAttivo', label: 'SQL thread' },
          { key: 'secondiDiRitardo', label: 'Ritardo (s)' },
          { key: 'relayLogBytes', label: 'Relay log', render: formatBytes },
          { key: 'ultimoErrore', label: 'Ultimo errore' },
        ]} empty="Nessun canale di replica attivo" />
      </Section>

      {r.connessioni && (
        <Section title="Connessioni attive">
          <p>Connessioni attive: <strong>{fmt(r.connessioni.attive)}</strong></p>
        </Section>
      )}

      {r.queryAttive != null && (
      <Section title="Query attive (per consumo)">
        <QueryTable rows={r.queryAttive} />
      </Section>
      )}

      {r.queryLente != null && (
      <Section title="Query più lente (per digest)">
        <p className="muted small">Clicca una query per vedere il piano di esecuzione.</p>
        <Table rows={r.queryLente} onRowClick={(q) => setPlan({ serverId: r.server.id, server: r.server.name, sql: q.QUERY, schema: q.SCHEMA_NAME })} columns={[
          { key: 'SCHEMA_NAME', label: 'Schema' },
          { key: 'QUERY', label: 'Query', render: (v) => <code>{fmt(v)}</code> },
          { key: 'ESECUZIONI', label: 'Esecuzioni' }, { key: 'MEDIA_SEC', label: 'Media (s)' },
          { key: 'MAX_SEC', label: 'Max (s)' }, { key: 'RIGHE_ESAMINATE', label: 'Righe esaminate' },
          { key: 'SENZA_INDICE', label: 'Senza indice' },
        ]} empty="Nessuna statistica disponibile" />
        {plan && <ExplainPanel plan={plan} onClose={() => setPlan(null)} />}
      </Section>
      )}

      {r.errori && Object.keys(r.errori).length > 0 && (
        <Section title="Sezioni non disponibili">
          <ul>{Object.entries(r.errori).map(([k, v]) => <li key={k}><strong>{k}:</strong> {v}</li>)}</ul>
        </Section>
      )}
    </div>
  );
}


/* ------------------------------------------------------------ TOPOLOGIA */

const NODE_W = 184;
const NODE_H = 134;

/* Colore dello stato di un nodo nella topologia. */
function topoColor(node) {
  if (node.up === false) return '#ef4444';
  if (node.up === null || node.up === undefined) return '#64748b';
  if (node.statoMembro && node.statoMembro !== 'ONLINE') return '#f59e0b';
  return '#22c55e';
}

/* Ritardo di allineamento: transazioni in coda per i membri del cluster, secondi per le repliche. */
function lagText(n) {
  if (n.gtidTransazioni != null) {
    return `gtid ${n.gtidTransazioni}${n.codaTransazioni != null ? ` · coda ${n.codaTransazioni}` : ''}`;
  }
  if (n.codaTransazioni != null) return `coda ${n.codaTransazioni} tx`;
  if (n.ritardoSec != null) {
    return `relay ${formatBytes(n.relayLogBytes ?? 0)} · ritardo ${n.ritardoSec} s`;
  }
  return null;
}

function lagWarn(n) {
  return n.codaTransazioni > 100 || n.ritardoSec > 60 || n.errant > 0;
}

/* Punti di un esagono (orientamento a punta in alto), per le icone della topologia. */
function hexPoints(cx, cy, r) {
  return [0, 1, 2, 3, 4, 5].map((k) => {
    const a = (-90 + k * 60) * (Math.PI / 180);
    return `${(cx + r * Math.cos(a)).toFixed(2)},${(cy + r * Math.sin(a)).toFixed(2)}`;
  }).join(' ');
}

/* Un nodo disegnato nel diagramma: nome, ruolo, versione e motore. */
function TopoNode({ x, y, node, roleLabel, onOpen, selected }) {
  const ghost = node.kind === 'non-in-anagrafica';
  const color = topoColor(node);
  const engineColor = node.motore === 'MariaDB' ? '#2dd4bf' : '#f59e0b';
  const clickable = !!node.id;
  return (
    <g
      transform={`translate(${x - NODE_W / 2},${y - NODE_H / 2})`}
      onClick={() => clickable && onOpen(node)}
      style={{ cursor: clickable ? 'pointer' : 'default' }}
    >
      <rect width={NODE_W} height={NODE_H} rx={13} fill="#132340" stroke={node.candidato ? '#facc15' : color}
        strokeWidth={selected || node.candidato ? 3.5 : 2} strokeDasharray={ghost ? '6 4' : undefined} />
      {node.candidato && (
        <text x={NODE_W - 34} y={22} className="topo-star"><title>Nodo da cui eseguire il ripristino</title>★</text>
      )}
      {(node.ruolo === 'PRIMARY' || node.ruolo === 'GALERA' || roleLabel === 'sorgente') && (
        <polygon className="topo-source-hex" points={hexPoints(NODE_W - 58, 16, 7)}>
          <title>{roleLabel === 'sorgente' ? 'Sorgente della replica' : 'Primario del cluster'}</title>
        </polygon>
      )}
      <circle cx={18} cy={NODE_H / 2} r={6} fill={color} />
      <text x={34} y={27} className="topo-name">{node.name}</text>
      {node.sito && <text x={34} y={43} className="topo-site">{node.sito}</text>}
      <text x={34} y={60} className="topo-sub">{roleLabel}{node.versione ? ` · ${node.versione}` : ''}</text>
      {lagText(node) && (
        <text x={34} y={78} className={`topo-lag ${lagWarn(node) ? 'warn' : 'ok'}`}>{lagText(node)}</text>
      )}
      {node.datiBytes != null && (
        <text x={34} y={96} className="topo-data">
          {`dati ${formatBytes(node.datiBytes)}`}
        </text>
      )}
      {node.errant > 0 && (
        <text x={34} y={114} className="topo-lag warn">{`errant ${node.errant} tx: non presenti sugli altri nodi`}</text>
      )}
      <circle cx={NODE_W - 16} cy={16} r={5.5} fill={engineColor} />
      <title>{`${node.name} — ${roleLabel}${node.errore ? ' — ' + node.errore : ''}`}</title>
    </g>
  );
}

/* Punto di uscita di una freccia dal bordo di un nodo, verso un altro punto. */
function edgePoint(from, to, k) {
  const dx = to.x - from.x, dy = to.y - from.y;
  const d = Math.hypot(dx, dy) || 1;
  return { x: from.x + (dx / d) * k, y: from.y + (dy / d) * k };
}

/* Diagramma di un cluster: membri su un anello, frecce del gruppo, flusso dal primario, router. */
function ClusterTopology({ group, onOpen, selectedId }) {
  const uid = useId().replace(/:/g, '');
  const nodi = group.nodi;
  const n = nodi.length;
  const W = 760, H = 440, cx = 480, cy = 222, R = n <= 3 ? 165 : 180;
  const pos = nodi.map((_, i) => {
    const a = -Math.PI / 2 + (i * 2 * Math.PI) / Math.max(n, 1);
    return { x: cx + R * Math.cos(a), y: cy + R * Math.sin(a) };
  });
  const primaryIdx = nodi.findIndex((x) => x.ruolo === 'PRIMARY');
  const k = 100;
  const galera = !!group.galera;

  // Galera: ogni coppia di nodi si scambia transazioni in entrambe le direzioni
  const mesh = galera ? nodi.flatMap((_, i) => nodi.slice(i + 1).map((__, jj) => {
    const j = i + 1 + jj;
    const a = edgePoint(pos[i], pos[j], k), b = edgePoint(pos[j], pos[i], k);
    return { d: `M${a.x},${a.y} L${b.x},${b.y}`, i, j };
  })) : [];

  // Frecce ad anello: comunicazione di gruppo tra membri consecutivi
  const ring = !galera && n >= 2 ? nodi.map((_, i) => {
    const j = (i + 1) % n;
    const mx = (pos[i].x + pos[j].x) / 2, my = (pos[i].y + pos[j].y) / 2;
    const ctrl = { x: cx + (mx - cx) * 1.55, y: cy + (my - cy) * 1.55 };
    const a = edgePoint(pos[i], ctrl, k), b = edgePoint(pos[j], ctrl, k);
    return { d: `M${a.x},${a.y} Q${ctrl.x},${ctrl.y} ${b.x},${b.y}`, i, j };
  }) : [];

  // Flusso dati dal primario verso i secondari
  const flows = !galera && primaryIdx >= 0 ? nodi.map((_, i) => i).filter((i) => i !== primaryIdx).map((i) => {
    const p = pos[primaryIdx], q = pos[i];
    const a = edgePoint(p, q, k), b = edgePoint(q, p, k);
    return { d: `M${a.x},${a.y} L${b.x},${b.y}`, i, bad: nodi[i].up === false, mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } };
  }) : [];

  // Router: traffico client verso primario (RW) e secondari (RO)
  const rx = 98, ry = cy, rw = 150, rh = 58;
  const routers = group.endpoint.map((e, idx) => ({ e, idx }));

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="topo" role="img" aria-label={`Topologia del cluster ${group.nome}`}>
      {group.richiedeRipristino && (
        <g className="topo-alert">
          <rect x={16} y={8} width={372} height={28} rx={8} fill="rgba(239,68,68,.18)" stroke="#ef4444" />
          <text x={28} y={27} className="topo-alert-text">RICHIEDE RIPRISTINO DAL BLACKOUT COMPLETO</text>
        </g>
      )}
      <defs>
        <marker id={`ring-${uid}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" fill="#7dd3fc" />
        </marker>
        <marker id={`flow-${uid}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto">
          <path d="M0,0 L10,5 L0,10 z" fill="#22d3ee" />
        </marker>
        <marker id={`flowbad-${uid}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto">
          <path d="M0,0 L10,5 L0,10 z" fill="#ef4444" />
        </marker>
        <marker id={`route-${uid}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto">
          <path d="M0,0 L10,5 L0,10 z" fill="#a78bfa" />
        </marker>
      </defs>

      {galera && mesh.map((m) => (
        <path key={`m${m.i}-${m.j}`} d={m.d} className="topo-galera"
          markerStart={`url(#ring-${uid})`} markerEnd={`url(#ring-${uid})`} />
      ))}

      {/* anello di comunicazione del gruppo */}
      {ring.map((r) => (
        <path key={`r${r.i}`} d={r.d} className="topo-ring" markerEnd={`url(#ring-${uid})`} />
      ))}

      {/* flussi dati primario → secondari */}
      {flows.map((f) => (
        <path key={`f${f.i}`} d={f.d} className={f.bad ? 'topo-flow-bad' : 'topo-flow'}
          markerEnd={`url(#${f.bad ? 'flowbad' : 'flow'}-${uid})`} />
      ))}

      {flows.map((f) => nodi[f.i].codaTransazioni != null && (
        <text key={`fl${f.i}`} x={f.mid.x + 8} y={f.mid.y - 8} className={`topo-label ${lagWarn(nodi[f.i]) ? 'warn' : ''}`}>
          coda {nodi[f.i].codaTransazioni} tx
        </text>
      ))}

      {/* router e traffico client */}
      {routers.map(({ e, idx }) => {
        const y = ry + (idx - (routers.length - 1) / 2) * 90;
        const down = e.up === false;
        return (
          <g key={e.id} onClick={() => onOpen(e)} style={{ cursor: 'pointer' }}>
            <rect x={rx - rw / 2} y={y - rh / 2} width={rw} height={rh} rx={12}
              fill="#1e1b3a" stroke={down ? '#ef4444' : '#a78bfa'} strokeWidth={2} />
            <text x={rx} y={y - 4} textAnchor="middle" className="topo-name">{e.name}</text>
            <text x={rx} y={y + 14} textAnchor="middle" className="topo-sub">router · {e.port}</text>
            {nodi.map((m, i) => {
              const isPrimary = i === primaryIdx;
              const pt = edgePoint(pos[i], { x: rx, y }, k);
              const st = edgePoint({ x: rx, y }, pos[i], rw / 2 + 4);
              return (
                <path key={`${e.id}-${i}`} d={`M${st.x},${st.y} Q${(st.x + pt.x) / 2},${(st.y + pt.y) / 2 - 20} ${pt.x},${pt.y}`}
                  className={isPrimary ? 'topo-route rw' : 'topo-route ro'} markerEnd={`url(#route-${uid})`} />
              );
            })}
          </g>
        );
      })}

      {/* membri del cluster */}
      {nodi.map((m, i) => (
        <TopoNode key={m.id || m.name + i} x={pos[i].x} y={pos[i].y}
          node={group.candidatoRipristino?.nome === m.name ? { ...m, candidato: true } : m}
          roleLabel={m.ruolo || 'membro'} onOpen={onOpen} selected={selectedId === m.id} />
      ))}

      {/* legenda */}
      <g transform={`translate(20,${H - 62})`} className="topo-legend">
        {galera ? (
          <>
            <line x1="0" y1="8" x2="34" y2="8" className="topo-galera" />
            <text x="42" y="12">replica sincrona Galera: ogni nodo scrive e replica sugli altri</text>
          </>
        ) : (
          <>
            <line x1="0" y1="8" x2="34" y2="8" className="topo-flow" />
            <text x="42" y="12">dati primario → secondari</text>
            <line x1="0" y1="30" x2="34" y2="30" className="topo-ring" />
            <text x="42" y="34">comunicazione di gruppo</text>
            <line x1="0" y1="52" x2="34" y2="52" className="topo-route ro" />
            <text x="42" y="56">traffico client via router</text>
          </>
        )}
      </g>
    </svg>
  );
}

/* Diagramma di un replica set: sorgente in alto, repliche sotto con i flussi binlog. */
function ReplicaTopology({ source, repliche, sorgente, onOpen, selectedId }) {
  const uid = useId().replace(/:/g, '');
  const n = repliche.length;
  const W = 760, H = 330;
  const sx = W / 2, sy = 78;
  const ry = 256;
  const pos = repliche.map((_, i) => ({ x: (W * (i + 1)) / (n + 1), y: ry }));
  const src = source || { name: sorgente, kind: 'non-in-anagrafica', up: null, id: null };
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="topo" role="img" aria-label={`Replica set da ${sorgente}`}>
      <defs>
        <marker id={`binlog-${uid}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto">
          <path d="M0,0 L10,5 L0,10 z" fill="#c084fc" />
        </marker>
        <marker id={`binlogbad-${uid}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto">
          <path d="M0,0 L10,5 L0,10 z" fill="#ef4444" />
        </marker>
      </defs>
      {pos.map((p, i) => {
        const bad = repliche[i].up === false;
        const sy0 = sy + NODE_H / 2, ty = p.y - NODE_H / 2 - 4;
        const mid = (sy0 + ty) / 2;
        return (
          <g key={`e${i}`}>
            <path d={`M${sx},${sy0} C${sx},${mid} ${p.x},${mid} ${p.x},${ty}`}
              className={bad ? 'topo-flow-bad' : 'topo-flow binlog'}
              markerEnd={`url(#${bad ? 'binlogbad' : 'binlog'}-${uid})`} />
            <text x={(sx + p.x) / 2 + 6} y={mid - 6} className={`topo-label ${lagWarn(repliche[i]) ? 'warn' : ''}`}>
              binlog → relay log{repliche[i].ritardoSec != null ? ` · ${formatBytes(repliche[i].relayLogBytes ?? 0)} · ritardo ${repliche[i].ritardoSec} s` : ''}
            </text>
          </g>
        );
      })}
      <TopoNode x={sx} y={sy} node={src} roleLabel="sorgente" onOpen={onOpen} selected={selectedId === src.id} />
      {repliche.map((r, i) => (
        <TopoNode key={r.id} x={pos[i].x} y={pos[i].y} node={r} roleLabel="replica" onOpen={onOpen}
          selected={selectedId === r.id} />
      ))}
      <g transform={`translate(20,${H - 26})`} className="topo-legend">
        <line x1="0" y1="-4" x2="34" y2="-4" className="topo-flow binlog" />
        <text x="42" y="0">replica asincrona: il master invia il binlog agli slave</text>
      </g>
    </svg>
  );
}


/* ------------------------------------------------------- STATO E SINTESI */

const RANK = { red: 3, yellow: 2, grey: 1, green: 0 };

/* Stato di un nodo: rosso allarme, giallo warning, verde ok, grigio in attesa. */
function statusOf(n) {
  if (n.kind === 'non-in-anagrafica') {
    const red = n.statoMembro && n.statoMembro !== 'ONLINE' ? [`membro ${n.statoMembro}`] : [];
    return red.length
      ? { level: 'red', reasons: red }
      : { level: 'yellow', reasons: ['non registrato in anagrafica'] };
  }
  if (n.up === null || n.up === undefined) return { level: 'grey', reasons: ['in attesa del primo rilevamento'] };
  if (n.up === false) return { level: 'red', reasons: [`non raggiungibile${n.errore ? ': ' + n.errore : ''}`] };
  const red = [];
  const yellow = [];
  if (n.statoMembro && n.statoMembro !== 'ONLINE') red.push(`membro ${n.statoMembro}`);
  if (n.replicaOk === false) red.push('replica non attiva o con errore');
  if (n.ritardoSec > 60) yellow.push(`ritardo replica ${n.ritardoSec} s`);
  if (n.errant > 0) yellow.push(`${n.errant} transazioni errant`);
  if (n.codaTransazioni > 100) yellow.push(`coda ${n.codaTransazioni} tx`);
  const level = red.length ? 'red' : yellow.length ? 'yellow' : 'green';
  return { level, reasons: [...red, ...yellow] };
}

/* Il livello peggiore tra una lista di livelli. */
function worst(levels) {
  return levels.reduce((a, b) => (RANK[b] > RANK[a] ? b : a), 'green');
}

const LEVEL_SYMBOL = { red: '!', yellow: '▲', green: '✓', grey: '…' };

/* Quadratino di stato: cliccabile apre il report del nodo. */
/* Colore di ogni livello di stato, usato per il bordo dell'esagono. */
const COLORI_HEX = { red: '#ef4444', yellow: '#f59e0b', green: '#22c55e', grey: '#64748b' };

/* Icona esagonale: un esagono dentro l'altro, il bordo esterno dello stesso colore del livello. */
function HexIcona({ livello, simbolo }) {
  const c = COLORI_HEX[livello] || COLORI_HEX.grey;
  const id = `hx-${livello}`;
  return (
    <svg className="hexicon-svg" viewBox="0 0 100 100" width="68" height="68" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={`${id}-fondo`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#1e293b" />
          <stop offset="1" stopColor="#0b1220" />
        </linearGradient>
        <radialGradient id={`${id}-cuore`}>
          <stop offset="0" stopColor={c} stopOpacity="0.42" />
          <stop offset="1" stopColor={c} stopOpacity="0.06" />
        </radialGradient>
        <filter id={`${id}-bagliore`} x="-30%" y="-30%" width="160%" height="160%">
          <feDropShadow dx="0" dy="0" stdDeviation="2.5" floodColor={c} floodOpacity="0.75" />
        </filter>
      </defs>
      <polygon points={hexPoints(50, 50, 46)} fill={`url(#${id}-fondo)`} stroke={c} strokeWidth="6"
        strokeLinejoin="round" filter={`url(#${id}-bagliore)`} />
      <polygon points={hexPoints(50, 50, 35)} fill="none" stroke={c} strokeOpacity="0.35" strokeWidth="1.5" />
      <polygon points={hexPoints(50, 50, 26)} fill={`url(#${id}-cuore)`} stroke={c} strokeWidth="2" />
      <text x="50" y="58" textAnchor="middle" fontSize="24" fontWeight="900" fill={c}>{simbolo}</text>
    </svg>
  );
}

function Tile({ node, onOpen, selected, sorgente = false }) {
  const st = statusOf(node);
  const tip = [node.name, ...st.reasons].join(' · ');
  const isSorgente = sorgente || node.ruolo === 'PRIMARY' || node.ruolo === 'GALERA';
  return (
    <button
      type="button"
      className={`tile ${st.level} ${selected ? 'selected' : ''}`}
      title={tip}
      onClick={() => node.id && onOpen(node)}
      disabled={!node.id}
    >
      <span className="tile-sq-wrap">
        <span className="tile-sq hexicon"><HexIcona livello={st.level} simbolo={LEVEL_SYMBOL[st.level]} /></span>
        {node.vettoriale && (
          <svg className="tile-ai" viewBox="0 0 20 20" width="20" height="20" aria-label="Vector database">
            <title>Vector database (colonne VECTOR presenti)</title>
            <polygon points={hexPoints(10, 10, 9)} fill="#6366f1" stroke="#c4b5fd" strokeWidth="1" />
            <text x="10" y="13.2" textAnchor="middle" fontSize="7" fontWeight="900" fill="#fff">AI</text>
          </svg>
        )}
        {isSorgente && (
          <svg className="tile-src" viewBox="0 0 20 20" width="18" height="18" aria-label={sorgente ? 'Sorgente della replica' : node.ruolo === 'GALERA' ? 'Sorgente di scrittura (Galera)' : 'Primario del cluster'}>
            <title>{sorgente ? 'Sorgente della replica' : node.ruolo === 'GALERA' ? 'Sorgente di scrittura (Galera)' : 'Primario del cluster'}</title>
            <polygon points={hexPoints(10, 10, 9)} fill="#facc15" stroke="#0b1220" strokeWidth="1.2" />
          </svg>
        )}
      </span>
      <span className="tile-label">{node.name}</span>
      {node.sito && <span className="tile-site">{node.sito}</span>}
      {st.reasons.length > 0 && <span className="tile-reason">{st.reasons[0]}</span>}
    </button>
  );
}

function Legend() {
  return (
    <div className="legend">
      <span><i className="dot red" /> allarme</span>
      <span><i className="dot yellow" /> warning</span>
      <span><i className="dot green" /> tutto ok</span>
      <span><i className="dot grey" /> in attesa</span>
    </div>
  );
}

/* Sintesi di un cluster: solo quadratini, il dettaglio si apre con il pulsante. */
function ClusterSummary({ group, onOpen, onDetail, selectedId }) {
  const levels = [
    ...group.nodi.map((n) => statusOf(n).level),
    ...group.endpoint.map((n) => statusOf(n).level),
    group.stato === 'IN ATTESA' ? 'grey' : group.stato && group.stato !== 'ONLINE' ? 'red' : 'green',
    group.richiedeRipristino ? 'red' : 'green',
  ];
  const livello = worst(levels);
  return (
    <section className="set cluster-set">
      <div className="set-head">
        <div className="set-title">
          <h3>Cluster {group.nome}</h3>
          <span className="set-kind">{group.galera ? 'Galera Cluster' : 'InnoDB Cluster'}</span>
          <Badge value={group.stato || 'sconosciuto'} />
          <span className={`tile-badge ${livello}`}>{livello === 'green' ? 'OK' : livello === 'red' ? 'ALLARME' : livello === 'yellow' ? 'WARNING' : 'IN ATTESA'}</span>
          {group.richiedeRipristino && <span className="tile-badge red">RIPRISTINO RICHIESTO</span>}
        </div>
        <button type="button" className="secondary" onClick={onDetail}>Dettaglio topologia →</button>
      </div>
      {group.richiedeRipristino && (
        <div className="warn-banner">
          {group.galera
            ? 'Cluster Galera fermo: nessun nodo è primary. Avviare come nuovo cluster solo il nodo candidato, poi riunire gli altri.'
            : <>Cluster spento: richiede il ripristino dal blackout completo (<code>dba.rebootClusterFromCompleteOutage()</code>).</>}
        </div>
      )}
      {group.richiedeRipristino && <WinnerBox group={group} />}
      <div className="tiles-caption">Membri · {group.galera ? 'multi-primario' : `primario ${fmt(group.primario)}`} · {group.onlineNodi ?? 0}/{group.nodi.length} online</div>
      <div className="tiles">
        {group.nodi.map((n, i) => (
          <Tile key={n.id || n.name + i} node={n} onOpen={onOpen} selected={selectedId === n.id} />
        ))}
      </div>
      {group.endpoint.length > 0 && (
        <>
          <div className="tiles-caption">Router</div>
          <div className="tiles">
            {group.endpoint.map((e) => (
              <Tile key={e.id} node={e} onOpen={onOpen} selected={selectedId === e.id} />
            ))}
          </div>
        </>
      )}
    </section>
  );
}

/* Sintesi di un replica set: sorgente e repliche come quadratini. */
function ReplicaSummary({ set, onOpen, onDetail, selectedId }) {
  const sorgenteLivello = set.master ? statusOf(set.master).level : 'yellow';
  const livello = worst([sorgenteLivello, ...set.repliche.map((r) => statusOf(r).level)]);
  return (
    <section className="set replica-set">
      <div className="set-head">
        <div className="set-title">
          <h3>Replica set</h3>
          <span className="set-kind">{set.repliche.length} {set.repliche.length === 1 ? 'replica' : 'repliche'}</span>
          <span className={`tile-badge ${livello}`}>{livello === 'green' ? 'OK' : livello === 'red' ? 'ALLARME' : livello === 'yellow' ? 'WARNING' : 'IN ATTESA'}</span>
        </div>
        <div className="set-actions">
          <span className="muted">sorgente {set.sorgente}</span>
          <button type="button" className="secondary" onClick={onDetail}>Dettaglio topologia →</button>
        </div>
      </div>
      <div className="tiles-caption">Sorgente</div>
      <div className="tiles">
        {set.master
          ? <Tile node={set.master} onOpen={onOpen} selected={selectedId === set.master.id} sorgente />
          : <div className="tile yellow"><span className="tile-sq hexicon"><HexIcona livello="yellow" simbolo="▲" /></span><span className="tile-label">{set.sorgente}</span><span className="tile-reason">non in anagrafica</span></div>}
      </div>
      <div className="tiles-caption">Repliche</div>
      <div className="tiles">
        {set.repliche.map((r) => (
          <Tile key={r.id} node={r} onOpen={onOpen} selected={selectedId === r.id} />
        ))}
      </div>
    </section>
  );
}

/* Nodi singoli come quadratini. */
function SingleSummary({ nodes, onOpen, selectedId }) {
  return (
    <section className="set single-set">
      <div className="set-head">
        <div className="set-title"><h3>Nodi singoli</h3><span className="set-kind">{nodes.length}</span></div>
      </div>
      <div className="tiles">
        {nodes.map((n) => (
          <Tile key={n.id} node={n} onOpen={onOpen} selected={selectedId === n.id} />
        ))}
      </div>
    </section>
  );
}

/* Elenco dei problemi di un insieme: nodo, livello e motivo. */
function IssueList({ nodes, extra = [] }) {
  const items = [
    ...extra,
    ...nodes.filter((n) => statusOf(n).level !== 'green').map((n) => ({ name: n.name, ...statusOf(n) })),
  ];
  if (items.length === 0) return <p className="ok-text">Nessun problema rilevato.</p>;
  return (
    <ul className="issues">
      {items.map((i, k) => (
        <li key={k} className={i.level}>
          <span className={`tile-badge ${i.level}`}>{LEVEL_SYMBOL[i.level]}</span>
          <strong>{i.name}</strong> — {i.reasons.join(', ')}
        </li>
      ))}
    </ul>
  );
}

/* Dettaglio a schermo intero di un cluster o di un replica set: topologia e problemi. */
function DetailView({ detail, data, onClose, onOpen, selectedId }) {
  if (detail.tipo === 'cluster') {
    const g = data.cluster.find((x) => x.nome === detail.key);
    if (!g) return <Missing onClose={onClose} />;
    const extra = [
      ...(g.stato && g.stato !== 'ONLINE' && g.stato !== 'IN ATTESA' ? [{ name: `Cluster ${g.nome}`, level: 'red', reasons: [`stato ${g.stato}`] }] : []),
      ...(g.richiedeRipristino ? [{ name: `Cluster ${g.nome}`, level: 'red', reasons: ['richiede ripristino dal blackout completo'] }] : []),
    ];
    return (
      <section className="set cluster-set detail">
        <div className="set-head">
          <div className="set-title">
            <button type="button" className="secondary" onClick={onClose}>← Sintesi</button>
            <h3>Cluster {g.nome}</h3>
            <span className="set-kind">{g.galera ? 'Galera Cluster' : 'InnoDB Cluster'}</span>
            <Badge value={g.stato || 'sconosciuto'} />
          </div>
          <span className="muted">{g.galera ? 'multi-primario · tutti i nodi scrivono' : `primario ${fmt(g.primario)}`} · {g.onlineNodi ?? 0}/{g.nodi.length} online</span>
        </div>
        {g.richiedeRipristino && <WinnerBox group={g} />}
        <div className="topo-wrap">
          <ClusterTopology group={g} onOpen={onOpen} selectedId={selectedId} />
        </div>
        <h4>Problemi</h4>
        <IssueList nodes={[...g.nodi, ...g.endpoint]} extra={extra} />
      </section>
    );
  }
  const { sets } = groupReplicas(data.singoli);
  const g = sets.find((x) => x.sorgente === detail.key);
  if (!g) return <Missing onClose={onClose} />;
  return (
    <section className="set replica-set detail">
      <div className="set-head">
        <div className="set-title">
          <button type="button" className="secondary" onClick={onClose}>← Sintesi</button>
          <h3>Replica set</h3>
          <span className="set-kind">{g.repliche.length} {g.repliche.length === 1 ? 'replica' : 'repliche'}</span>
        </div>
        <span className="muted">sorgente {g.sorgente}</span>
      </div>
      <div className="topo-wrap">
        <ReplicaTopology source={g.master} repliche={g.repliche} sorgente={g.sorgente} onOpen={onOpen} selectedId={selectedId} />
      </div>
      <h4>Problemi</h4>
      <IssueList nodes={[...(g.master ? [g.master] : []), ...g.repliche]} />
    </section>
  );
}

/* Nodo da cui eseguire il comando di ripristino, con il motivo della scelta. */
function WinnerBox({ group }) {
  const w = group.candidatoRipristino;
  if (!w) return null;
  return (
    <div className="winner-box">
      <span className="winner-star">★</span>
      <div>
        <div>
          {group.galera ? 'Nodo candidato al bootstrap' : 'Esegui il ripristino da'} <strong>{w.nome || 'nessun nodo'}</strong>
          {w.nome && !group.galera && <code> dba.rebootClusterFromCompleteOutage()</code>}
          {w.provvisorio && <span className="tile-badge yellow" style={{ marginLeft: 8 }}>PROVVISORIO</span>}
        </div>
        <div className="muted small">
          {w.motivo}
          {w.transazioni != null && ` · errant ${w.errant ?? 0}`}
          {w.nonLetti && ` · stato non leggibile: ${w.nonLetti.join(', ')}`}
        </div>
      </div>
    </div>
  );
}

function Missing({ onClose }) {
  return (
    <section className="card">
      <p className="muted">Questo insieme non è più presente nei dati rilevati.</p>
      <button type="button" className="secondary" onClick={onClose}>← Sintesi</button>
    </section>
  );
}

/* ------------------------------------------------------------------- REPORT */

const SEVERITY_COLORS = { CRITICAL: '#ef4444', WARNING: '#f59e0b', INFO: '#38bdf8' };

/* Grafico a barre impilate in SVG: una barra per ora, una fetta per serie. */
function StackedBars({ data, series, label }) {
  const W = 760, H = 260, padL = 44, padB = 40, padT = 14;
  const totals = data.map((d) => series.reduce((s, x) => s + Number(d[x.key] ?? 0), 0));
  const max = Math.max(1, ...totals);
  const innerH = H - padB - padT;
  const bw = (W - padL - 10) / Math.max(data.length, 1);
  if (data.length === 0) return <p className="muted">Nessun dato nel periodo selezionato</p>;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label={label}>
      {[0, 0.5, 1].map((f) => {
        const y = H - padB - innerH * f;
        return (
          <g key={f}>
            <line x1={padL} x2={W - 6} y1={y} y2={y} className="chart-grid" />
            <text x={padL - 6} y={y + 4} textAnchor="end" className="chart-axis">{Math.round(max * f)}</text>
          </g>
        );
      })}
      {data.map((d, i) => {
        let y = H - padB;
        const x = padL + i * bw + 2;
        return (
          <g key={d.ora}>
            {series.map((sr) => {
              const v = Number(d[sr.key] ?? 0);
              const h = (v / max) * innerH;
              y -= h;
              return (
                <rect key={sr.key} x={x} y={y} width={Math.max(bw - 4, 2)} height={h} fill={sr.color} rx="2">
                  <title>{`${d.ora} · ${sr.label}: ${v}`}</title>
                </rect>
              );
            })}
            {(i % Math.ceil(data.length / 8) === 0) && (
              <text x={x + bw / 2} y={H - padB + 16} textAnchor="middle" className="chart-axis">
                {String(d.ora).slice(5, 16)}
              </text>
            )}
          </g>
        );
      })}
      <g transform={`translate(${padL},${H - 8})`} className="chart-legend">
        {series.map((sr, i) => (
          <g key={sr.key} transform={`translate(${i * 150},0)`}>
            <rect width="10" height="10" y="-9" rx="2" fill={sr.color} />
            <text x="16" y="0">{sr.label}</text>
          </g>
        ))}
      </g>
    </svg>
  );
}

function ReportTab() {
  const [ore, setOre] = useState(24);
  const [dati, setDati] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let alive = true;
    api(`/summary?ore=${ore}`)
      .then((r) => alive && setDati(r))
      .catch((e) => alive && setError(e.message));
    return () => { alive = false; };
  }, [ore]);

  const disp = dati?.disponibilita ?? [];
  const eventi = dati?.eventi ?? [];
  const eventiPerOra = Object.values(eventi.reduce((acc, e) => {
    acc[e.ora] = acc[e.ora] || { ora: e.ora };
    acc[e.ora][e.severity] = (acc[e.ora][e.severity] || 0) + Number(e.n);
    return acc;
  }, {}));
  const totSu = disp.reduce((s, d) => s + Number(d.su), 0);
  const totGiu = disp.reduce((s, d) => s + Number(d.giu), 0);
  const disponibilita = totSu + totGiu ? Math.round((totSu * 1000) / (totSu + totGiu)) / 10 : null;
  const contaEventi = (sev) => eventi.filter((e) => e.severity === sev).reduce((s, e) => s + Number(e.n), 0);

  return (
    <>
      <div className="toolbar">
        <span className="muted">Riepilogo dei rilevamenti e degli eventi nel periodo</span>
        <label className="refresh-select">
          Periodo
          <select value={ore} onChange={(e) => setOre(Number(e.target.value))}>
            <option value={24}>ultime 24 ore</option>
            <option value={168}>ultimi 7 giorni</option>
          </select>
        </label>
      </div>
      {error && <p className="bad-text banner">{error}</p>}

      <div className="kpis">
        <div className="kpi"><span className="kpi-label">Disponibilità</span><strong>{disponibilita == null ? '—' : `${disponibilita}%`}</strong></div>
        <div className="kpi"><span className="kpi-label">Rilevamenti</span><strong>{totSu + totGiu}</strong></div>
        <div className="kpi crit"><span className="kpi-label">Critici</span><strong>{contaEventi('CRITICAL')}</strong></div>
        <div className="kpi warn"><span className="kpi-label">Warning</span><strong>{contaEventi('WARNING')}</strong></div>
        <div className="kpi info"><span className="kpi-label">Info</span><strong>{contaEventi('INFO')}</strong></div>
      </div>

      <section className="card">
        <div className="card-head"><h3>Disponibilità dei server</h3><span className="muted">rilevamenti raggiungibili e non raggiungibili per ora</span></div>
        <StackedBars data={disp} label="Disponibilità per ora"
          series={[{ key: 'su', label: 'raggiungibili', color: '#22c55e' }, { key: 'giu', label: 'non raggiungibili', color: '#ef4444' }]} />
      </section>

      <section className="card">
        <div className="card-head"><h3>Eventi rilevati</h3><span className="muted">eventi per ora, divisi per gravità</span></div>
        <StackedBars data={eventiPerOra} label="Eventi per ora"
          series={[
            { key: 'CRITICAL', label: 'critici', color: SEVERITY_COLORS.CRITICAL },
            { key: 'WARNING', label: 'warning', color: SEVERITY_COLORS.WARNING },
            { key: 'INFO', label: 'info', color: SEVERITY_COLORS.INFO },
          ]} />
      </section>
    </>
  );
}

/* ------------------------------------------------------------------- HELP */

/* Sezioni del programma: cosa fanno e chi può usarle. Le frequenze arrivano dal database (v = valore corrente). */
const sezioniHelp = (v) => [
  {
    titolo: 'Dashboard di monitoraggio',
    chi: 'tutti',
    testo: `Stato in tempo reale di tutti i server visibili. Il controllo di raggiungibilità avviene ogni ${v('rilevamento.raggiungibilita_secondi')} s; lo stato generale è rilevato ogni ${v('rilevamento.leggero_minuti')} min e le sezioni pesanti ogni ${v('rilevamento.pesante_minuti')} min. In alto i contatori per stato; a sinistra cluster, repliche e server singoli con i quadratini esagonali colorati; a destra gli avvisi (problemi da controllare, eventi). Il filtro Ambiente mostra solo produzione, certificazione, collaudo o i server senza ambiente. Cliccando un server si apre il suo report; "Rileva ora" forza un rilevamento completo.`,
  },
  {
    titolo: 'Insight',
    chi: 'tutti',
    testo: `Health check giornaliero di MySQL e MariaDB: punteggio di salute per server, controlli con esito, raccomandazione e fonte. Il report è generato ogni giorno alle ${String(v('insight.ora')).padStart(2, '0')}:00, con la data di generazione e quella della prossima produzione. Si può rigenerare subito con "Genera ora".`,
  },
  {
    titolo: 'Ricerca',
    chi: 'tutti',
    testo: 'Cerca un database per nome in tutti i server MySQL e MariaDB visibili. Mostra in quale server e ambiente si trova e segnala i server che non hanno risposto.',
  },
  {
    titolo: 'Report',
    chi: 'tutti',
    testo: `Il dettaglio completo di un server: identità e versione, topologia, membri del cluster, stato di replica, risorse, query attive (le prime ${v('query.attive_limite')}) e query più lente (le prime ${v('query.lente_limite')}, cliccabili per il piano di esecuzione), indici e utenti. Serve per capire un problema in profondità.`,
  },
  {
    titolo: 'Configurazione',
    chi: "amministratore per parametri, server e utenti; utente solo per la propria password",
    testo: 'Per l\'amministratore: Parametri di rilevamento (frequenze di controllo, limiti delle query, conservazione dei dati, ora di Insight), Server da monitorare (anagrafica, ambiente, test di connessione) e Utenti (creazione, ruolo, server assegnati, reset password). Per tutti gli utenti: il reset della propria password.',
  },
  {
    titolo: 'Piano di esecuzione',
    chi: 'utenti e amministratori',
    testo: 'Si apre cliccando una query attiva o una query lenta. Mostra il piano EXPLAIN come grafico: scansioni, ricerche su indice, ordinamenti, costo di ciascun passo. La query non viene eseguita.',
  },
]

function HelpTab() {
  const [param, setParam] = useState(null);
  useEffect(() => {
    api('/frequenze').then(setParam).catch(() => setParam([]));
  }, []);
  const v = (chiave) => {
    const p = (param || []).find((x) => x.chiave === chiave);
    return p ? p.valore : '…';
  };
  const sezioni = sezioniHelp(v);
  return (
    <>
      <Section title="Frequenze di rilevamento attuali">
        <p className="muted">Valori letti dal database a ogni apertura di questa pagina: se l'amministratore li cambia, qui compaiono i nuovi.</p>
        {param === null && <p className="muted">Caricamento…</p>}
        {param && param.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Parametro</th><th>Valore attuale</th><th>Predefinito</th></tr></thead>
              <tbody>
                {param.map((p) => (
                  <tr key={p.chiave}>
                    <td>{p.descrizione}</td>
                    <td><strong>{p.valore} {p.unita}</strong></td>
                    <td className="muted">{p.predefinito} {p.unita}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>
      <Section title="Le sezioni del programma">
        <p className="muted">
          Ogni sezione è nel menu in alto. Le funzioni che non vedi non sono disponibili per il tuo ruolo.
          Ruoli: <strong>amministratore</strong> (configura tutto e vede tutti i server) e <strong>utente</strong>
          (vede i server assegnati e può fare test e rilevamenti).
        </p>
        <div className="help-sezioni">
          {sezioni.map((x) => (
            <article key={x.titolo} className="help-sezione">
              <h4>{x.titolo}</h4>
              <p>{x.testo}</p>
              <span className="help-chi">Accesso: {x.chi}</span>
            </article>
          ))}
        </div>
      </Section>
      <Section title="Sommario">
        <ul className="help-sommario">
          {sezioni.map((x) => (
            <li key={x.titolo}><strong>{x.titolo}</strong> — {x.testo.split('. ')[0]}.</li>
          ))}
        </ul>
      </Section>
    </>
  );
}

/* ---------------------------------------------------------------------- APP */

/* Logo: tre esagoni collegati, quello in alto e quello in basso sovrapposti, quello a destra attaccato. */
function Logo() {
  const hex = (cx, cy, r) => {
    const pts = [0, 1, 2, 3, 4, 5].map((k) => {
      const a = (-90 + k * 60) * (Math.PI / 180);
      return `${(cx + r * Math.cos(a)).toFixed(2)},${(cy + r * Math.sin(a)).toFixed(2)}`;
    });
    return pts.join(' ');
  };
  return (
    <svg className="logo" width="62" height="60" viewBox="0 0 62 60" aria-hidden="true">
      <defs>
        <linearGradient id="lg1" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#38bdf8" /><stop offset="1" stopColor="#6366f1" /></linearGradient>
        <linearGradient id="lg2" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#2dd4bf" /><stop offset="1" stopColor="#0ea5e9" /></linearGradient>
        <linearGradient id="lg3" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#a78bfa" /><stop offset="1" stopColor="#ec4899" /></linearGradient>
      </defs>
      <polygon points={hex(24, 17, 16)} fill="url(#lg1)" fillOpacity="0.85" stroke="#7dd3fc" strokeWidth="1" />
      <polygon points={hex(24, 33, 16)} fill="url(#lg2)" fillOpacity="0.8" stroke="#5eead4" strokeWidth="1" />
      <polygon points={hex(47, 25, 14)} fill="url(#lg3)" fillOpacity="0.85" stroke="#c4b5fd" strokeWidth="1" />
    </svg>
  );
}

/* Intestazione: icona, nome del programma, sottotitolo e versione. */
function Brand() {
  return (
    <div className="brand">
      <Logo />
      <div>
        <h1>DBGuardian</h1>
        <div className="brand-line">
          <span className="brand-sub">Database Monitor Tool</span>
          <span className="brand-agent">Agent Free</span>
          <span className="brand-version">Basic Alfa</span>
        </div>
      </div>
    </div>
  );
}

/* Pagine dell'applicazione, nell'ordine del menu. */
/* ------------------------------------------------------ PERMESSI E CONFIGURAZIONE (solo amministratore)
   Tre profili, ciascuno con un insieme fisso di permessi: devono restare allineati a PERMESSI_PER_PROFILO in Auth.java.
   L'ambito sui server è separato: l'amministratore vede tutti i server, gli altri solo quelli assegnati. */

/* Permessi del menu e delle funzioni: il contesto contiene quelli dell'utente collegato. */
const PermessiContext = createContext([]);

const PROFILI = [
  { id: 'utente', label: 'Utente', descrizione: 'Vede la dashboard, gli eventi, i report, il piano delle query e i parametri dei server assegnati. Può eseguire test di connessione e rilevamenti.', permessi: ['monitoraggio', 'diagnostica'] },
  { id: 'amministratore', label: 'Amministratore', descrizione: 'Configura server, utenti e parametri di rilevamento, e vede tutti i server. Più amministratori sono possibili.', permessi: ['monitoraggio', 'diagnostica', 'configurazione'] },
];

const FUNZIONI = [
  { nome: 'Dashboard, eventi e storico', permesso: 'monitoraggio' },
  { nome: 'Report dei server', permesso: 'monitoraggio' },
  { nome: 'Test di connessione', permesso: 'diagnostica' },
  { nome: 'Piano di esecuzione (EXPLAIN)', permesso: 'diagnostica' },
  { nome: 'Vedere i parametri di rilevamento', permesso: 'monitoraggio' },
  { nome: 'Modificare i parametri di rilevamento', permesso: 'configurazione' },
  { nome: 'Configurazione di server e utenti', permesso: 'configurazione' },
];

const EMPTY_USER = { username: '', password: '', profilo: 'utente', servers: [] };

const nomeProfilo = (id) => PROFILI.find((p) => p.id === id)?.label || id;

/* Matrice funzioni × profili: mostra in una sola tabella cosa può fare ciascun profilo. */
function PermissionMatrix() {
  return (
    <div className="table-wrap">
      <table className="perm-table">
        <thead>
          <tr><th>Funzione</th>{PROFILI.map((p) => <th key={p.id}>{p.label}</th>)}</tr>
        </thead>
        <tbody>
          {FUNZIONI.map((f) => (
            <tr key={f.nome}>
              <td>{f.nome}</td>
              {PROFILI.map((p) => {
                const ok = p.permessi.includes(f.permesso);
                return <td key={p.id} className={ok ? 'perm-yes' : 'perm-no'}>{ok ? '✓' : '—'}</td>;
              })}
            </tr>
          ))}
          <tr>
            <td>Server visibili</td>
            {PROFILI.map((p) => <td key={p.id}>{p.id === 'amministratore' ? 'tutti' : 'assegnati'}</td>)}
          </tr>
        </tbody>
      </table>
    </div>
  );
}

/* Elenco di server con casella di selezione, per assegnarli a un utente. */
function ServerChecks({ servers, value, onChange }) {
  const toggle = (id) => onChange(value.includes(id) ? value.filter((x) => x !== id) : [...value, id]);
  if (servers.length === 0) return <span className="muted">Nessun server in anagrafica: crearlo nella sezione Server da monitorare.</span>;
  return (
    <div className="checks">
      {servers.map((s) => (
        <label key={s.id} className="check">
          <input type="checkbox" checked={value.includes(s.id)} onChange={() => toggle(s.id)} />
          <span>{s.name}</span>
          <span className="muted small">{s.host}:{s.port}</span>
        </label>
      ))}
    </div>
  );
}

/* Campi condivisi tra creazione e modifica: profilo, descrizione, matrice, server visibili. */
function PermissionFields({ profilo, onProfilo, servers, assegnati, onAssegnati }) {
  const scelto = PROFILI.find((p) => p.id === profilo) || PROFILI[0];
  const tutti = scelto.id === 'amministratore';
  return (
    <>
      <label>Profilo
        <select value={scelto.id} onChange={(e) => onProfilo(e.target.value)}>
          {PROFILI.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
        </select>
      </label>
      <p className="muted small">{scelto.descrizione}</p>
      <PermissionMatrix />
      {tutti ? (
        <p className="muted small">L'amministratore vede tutti i server, anche quelli aggiunti in seguito.</p>
      ) : (
        <div className="field">
          <span>Server visibili nel monitoraggio</span>
          <div className="bulk">
            <button type="button" className="secondary" onClick={() => onAssegnati(servers.map((s) => s.id))}>Seleziona tutti</button>
            <button type="button" className="secondary" onClick={() => onAssegnati([])}>Nessuno</button>
          </div>
          <ServerChecks servers={servers} value={assegnati} onChange={onAssegnati} />
        </div>
      )}
    </>
  );
}

/* Modifica profilo e server visibili di un utente. */
function EditUserDialog({ user, servers, onClose, onSaved }) {
  useEscape(onClose);
  const [profilo, setProfilo] = useState(user.profilo);
  const [assegnati, setAssegnati] = useState(user.servers);
  const [error, setError] = useState('');

  async function save(e) {
    e.preventDefault();
    setError('');
    try {
      await api(`/users/${user.id}`, { method: 'PUT', body: { profilo, servers: assegnati } });
      onSaved();
    } catch (err) { setError(err.message); }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <form className="modal wide" onMouseDown={(e) => e.stopPropagation()} onSubmit={save}>
        <h3>Permessi di {user.username}</h3>
        <PermissionFields profilo={profilo} onProfilo={setProfilo} servers={servers}
          assegnati={assegnati} onAssegnati={setAssegnati} />
        {error && <div className="login-error" role="alert">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="secondary" onClick={onClose}>Annulla</button>
          <button type="submit">Salva</button>
        </div>
      </form>
    </div>
  );
}

/* Reset della password di un utente da parte dell'amministratore (non richiede la password attuale). */
function ResetUserPasswordDialog({ user, onClose, onSaved }) {
  useEscape(onClose);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');

  async function save(e) {
    e.preventDefault();
    if (password !== confirm) { setError('La password e la conferma non coincidono'); return; }
    try {
      await api(`/users/${user.id}/password`, { method: 'POST', body: { password } });
      onSaved(`Password di ${user.username} reimpostata: le sessioni aperte sono chiuse`);
    } catch (err) { setError(err.message); }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <form className="modal" onMouseDown={(e) => e.stopPropagation()} onSubmit={save}>
        <h3>Reimposta password: {user.username}</h3>
        <label className="field"><span>Nuova password (almeno 6 caratteri)</span>
          <input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        <label className="field"><span>Conferma</span>
          <input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} /></label>
        {error && <div className="login-error" role="alert">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="secondary" onClick={onClose}>Annulla</button>
          <button type="submit" disabled={!password || !confirm}>Reimposta</button>
        </div>
      </form>
    </div>
  );
}

/* Area utenti: elenco, creazione con profilo e server, reset password. Riservata all'amministratore. */
function UsersTab({ me }) {
  const [users, setUsers] = useState([]);
  const [servers, setServers] = useState([]);
  const [nuovo, setNuovo] = useState(EMPTY_USER);
  const [editing, setEditing] = useState(null);
  const [resetting, setResetting] = useState(null);
  const [daEliminare, setDaEliminare] = useState(null);
  const [ownPassword, setOwnPassword] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);

  const load = useCallback(async () => {
    try {
      setUsers(await api('/users'));
      setServers(await api('/servers'));
      setError(null);
    } catch (e) { setError(e.message); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const nomiServer = (ids) => ids.map((id) => servers.find((s) => s.id === id)?.name || id).join(', ') || 'nessuno';

  async function create(e) {
    e.preventDefault();
    setError(null); setNotice(null);
    try {
      await api('/users', { method: 'POST', body: nuovo });
      setNotice(`Utente ${nuovo.username} creato con profilo ${nomeProfilo(nuovo.profilo)}`);
      setNuovo(EMPTY_USER);
      await load();
    } catch (err) { setError(err.message); }
  }

  function remove(u) {
    setDaEliminare(u);
  }

  async function eliminaConfermato() {
    const u = daEliminare;
    setDaEliminare(null);
    setError(null); setNotice(null);
    try {
      await api(`/users/${u.id}`, { method: 'DELETE' });
      setNotice(`Utente ${u.username} eliminato`);
      await load();
    } catch (err) { setError(err.message); }
  }

  return (
    <>
      {error && <div className="login-error" role="alert">{error}</div>}
      {notice && <p className="ok-text">{notice}</p>}

      <Section title="Utenti">
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Username</th><th>Profilo</th><th>Server visibili</th><th>Azioni</th></tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td><strong>{u.username}</strong>{u.username === me && <span className="muted small"> (tu)</span>}</td>
                  <td><span className={u.profilo === 'amministratore' ? 'tile-badge green' : 'tile-badge'}>{nomeProfilo(u.profilo)}</span></td>
                  <td>{u.profilo === 'amministratore' ? 'tutti' : nomiServer(u.servers)}</td>
                  <td className="actions">
                    <button className="secondary" onClick={() => setEditing(u)}>Permessi e server</button>
                    <button className="secondary" onClick={() => setResetting(u)}>Reimposta password</button>
                    <button className="danger" disabled={u.username === me} onClick={() => remove(u)}>Elimina</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      <Section title="Nuovo utente">
        <form onSubmit={create}>
          <div className="grid">
            <label>Username<input value={nuovo.username} onChange={(e) => setNuovo({ ...nuovo, username: e.target.value })} required /></label>
            <label>Password (almeno 6 caratteri)<input type="password" autoComplete="new-password" value={nuovo.password}
              onChange={(e) => setNuovo({ ...nuovo, password: e.target.value })} required /></label>
          </div>
          <PermissionFields profilo={nuovo.profilo} onProfilo={(p) => setNuovo({ ...nuovo, profilo: p })}
            servers={servers} assegnati={nuovo.servers} onAssegnati={(s) => setNuovo({ ...nuovo, servers: s })} />
          <div className="modal-actions"><button type="submit">Crea utente</button></div>
        </form>
      </Section>

      <Section title="La mia password" right={<button className="secondary" onClick={() => setOwnPassword(true)}>Reset della mia password</button>}>
        <p className="muted">Il reset della propria password richiede la password attuale. Dopo il cambio restano aperte le sessioni in corso.</p>
      </Section>

      {editing && (
        <EditUserDialog user={editing} servers={servers} onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); setNotice(`Permessi di ${editing.username} aggiornati`); load(); }} />
      )}
      {resetting && (
        <ResetUserPasswordDialog user={resetting} onClose={() => setResetting(null)}
          onSaved={(msg) => { setResetting(null); setNotice(msg); }} />
      )}
      {ownPassword && <ChangePasswordDialog onClose={() => setOwnPassword(false)} />}
      {daEliminare && (
        <ConfirmDialog messaggio={`Eliminare l'utente ${daEliminare.username}? Le sessioni aperte si chiudono.`}
          onConfirm={eliminaConfermato} onCancel={() => setDaEliminare(null)} />
      )}
    </>
  );
}

/* Storico delle modifiche alle variabili di configurazione dei server. Solo amministratore. */
function VariabiliPanel() {
  const [righe, setRighe] = useState(null);
  const [errore, setErrore] = useState(null);

  const carica = useCallback(async () => {
    try { setRighe(await api('/config-vars')); setErrore(null); } catch (e) { setErrore(e.message); }
  }, []);

  useEffect(() => { carica(); }, [carica]);

  if (!righe) return <p className="muted">{errore || 'Caricamento…'}</p>;

  return (
    <>
      <p className="muted small">
        Modifiche rilevate confrontando le variabili globali di ogni server ad ogni rilevamento pesante.
        Le variabili con gravità Attenzione sono quelle che incidono su sicurezza, durabilità o disponibilità.
      </p>
      {errore && <p className="bad-text">{errore}</p>}
      <Table rows={righe} empty="Nessuna modifica rilevata" columns={[
        { key: 'changed_at', label: 'Quando', render: fmtData },
        { key: 'server_name', label: 'Server' },
        { key: 'var_name', label: 'Variabile', render: (v) => <code>{v}</code> },
        { key: 'old_value', label: 'Prima', render: (v) => v ?? <span className="muted">(non presente)</span> },
        { key: 'new_value', label: 'Dopo', render: (v) => v ?? <span className="muted">(non presente)</span> },
        { key: 'severity', label: 'Gravità', render: (v) => (v === 'WARNING' ? 'Attenzione' : 'Info') },
      ]} />
    </>
  );
}

/* ------------------------------------------- INDICI: server → database → indici */

/* Dieci indici più frammentati e dieci con più scritture che letture, per il database scelto. */
function IndiciPanel({ server, db }) {
  const [dati, setDati] = useState(null);
  const [errore, setErrore] = useState(null);
  const [caricamento, setCaricamento] = useState(false);

  useEffect(() => {
    setDati(null); setErrore(null);
    if (!server || !db) return undefined;
    let vivo = true;
    setCaricamento(true);
    api(`/statistiche/${server}/indici?db=${encodeURIComponent(db)}`)
      .then((r) => vivo && setDati(r))
      .catch((e) => vivo && setErrore(e.message))
      .finally(() => vivo && setCaricamento(false));
    return () => { vivo = false; };
  }, [server, db]);

  if (!server || !db) return <p className="muted">Scegliere un server e un database.</p>;
  if (caricamento) return <p className="muted">Analisi degli indici in corso…</p>;
  if (errore) return <p className="bad-text">{errore}</p>;
  if (!dati) return null;

  const colonne = [
    { key: 'oggetto', label: 'Oggetto' },
    { key: 'indice', label: 'Indice' },
    { key: 'letture', label: 'Letture', render: (v) => (v == null ? '—' : Number(v).toLocaleString('it-IT')) },
    { key: 'scritture', label: 'Scritture', render: (v) => (v == null ? '—' : Number(v).toLocaleString('it-IT')) },
    { key: 'rapporto', label: 'Scritture / letture', render: (v) => (v == null ? 'nessuna lettura' : Number(v).toFixed(2)) },
    { key: 'dettaglio', label: 'Dettaglio' },
  ];
  const colonneFrammentazione = [
    { key: 'oggetto', label: 'Oggetto' },
    { key: 'indice', label: 'Indice' },
    { key: 'frammentazione', label: 'Frammentazione %', render: (v) => Number(v).toFixed(1) },
    { key: 'dettaglio', label: 'Dettaglio' },
  ];

  return (
    <>
      <p className="muted small">{dati.tecnologia}</p>
      <h4>I 10 indici più frammentati</h4>
      <Table rows={dati.frammentati} columns={colonneFrammentazione} empty="Nessun indice frammentato rilevato" />
      <h4>I 10 indici con più scritture che letture</h4>
      <Table rows={dati.scritture} columns={colonne} empty="Nessun indice con scritture registrate" />
      {(dati.note || []).map((n, i) => <p key={i} className="muted small">{n}</p>)}
    </>
  );
}

function StatisticsPage() {
  const [servers, setServers] = useState([]);
  const [server, setServer] = useState('');
  const [dbs, setDbs] = useState([]);
  const [db, setDb] = useState('');
  const [errore, setErrore] = useState(null);
  const [topologia, setTopologia] = useState(null);

  const carica = useCallback(async () => {
    try { setServers(await api('/statistiche/server')); } catch (e) { setErrore(e.message); }
  }, []);
  useEffect(() => { carica(); }, [carica]);

  const fallisci = (e) => setErrore(e.message);

  function scegliServer(id) {
    setServer(id); setDb(''); setDbs([]); setErrore(null); setTopologia(null);
    if (id) api(`/statistiche/${id}/database`).then(setDbs).catch(fallisci);
    const scelto = servers.find((x) => x.id === id);
    if (id && scelto && scelto.kind === 'sqlserver') api(`/statistiche/${id}/topologia`).then(setTopologia).catch(fallisci);
  }
  function scegliDb(nome) { setDb(nome); setErrore(null); }

  const serverScelto = servers.find((x) => x.id === server);

  return (
    <>
      <section className="card">
        <h3>Indici</h3>
        <p className="muted small">
          Controllo in sola lettura. Server e database: scegliere in ordine.
          Gli indici mostrano i più frammentati e quelli con più scritture che letture.
          I server si aggiungono in Configurazione → Server da monitorare (MySQL, MariaDB, PostgreSQL, SQL Server).
        </p>
        <div className="stat-colonne">
          <div>
            <strong>1. Server</strong>
            <select value={server} onChange={(e) => scegliServer(e.target.value)} aria-label="Scegli il server">
              <option value="">{servers.length === 0 ? 'Nessun server disponibile' : 'Scegli un server…'}</option>
              {servers.map((s) => (
                <option key={s.id} value={s.id}>{s.name} · {s.tecnologia}</option>
              ))}
            </select>
          </div>
          <div>
            <strong>2. Database</strong>
            <div className="stat-lista">
              {dbs.map((d) => (
                <button key={d.name} className={db === d.name ? 'menu-item active' : 'menu-item'} onClick={() => scegliDb(d.name)}>{d.name}</button>
              ))}
            </div>
          </div>
        </div>
        {serverScelto && <p className="muted small">Tecnologia: {serverScelto.tecnologia} · {serverScelto.host}:{serverScelto.port}</p>}
        {errore && <p className="bad-text">{errore}</p>}
      </section>

      {topologia && (
        <section className="card">
          <h3>Topologia <span className="muted small">· {topologia.tipo}{topologia.geografica ? ' · geografica' : ''}</span></h3>
          <p>{topologia.descrizione}</p>
          {topologia.repliche.length > 0 && (
            <Table rows={topologia.repliche} columns={[
              { key: 'replica', label: 'Replica' },
              { key: 'ruolo', label: 'Ruolo', render: (v) => v || '—' },
              { key: 'modalita', label: 'Commit' },
              { key: 'failover', label: 'Failover' },
              { key: 'connessione', label: 'Connessione', render: (v) => v || '—' },
              { key: 'sincronizzazione', label: 'Sincronizzazione', render: (v) => v || '—' },
            ]} />
          )}
          {topologia.listener.length > 0 && (
            <Table rows={topologia.listener} columns={[
              { key: 'listener', label: 'Listener' },
              { key: 'indirizzo', label: 'Indirizzo' },
              { key: 'subnet', label: 'Subnet' },
            ]} />
          )}
          {topologia.avvisi.length > 0 && (
            <ul className="stat-esiti">
              {topologia.avvisi.map((a, i) => <li key={i} className="stat-warn">{a}</li>)}
            </ul>
          )}
        </section>
      )}

      <section className="card">
        <IndiciPanel server={server} db={db} />
      </section>
    </>
  );
}

/* Controlli che il monitor esegue per tecnologia: se non selezionati nell'anagrafica non vengono eseguiti. */
const CONTROLLI_COMUNI = [
  { k: 'connessioni', l: 'Connessioni attive (numero)' },
  { k: 'query_attive', l: 'Query attive (numero e dettaglio)' },
  { k: 'query_lente', l: 'Query più lente' },
  { k: 'utenti', l: 'Utenti: data di creazione e scadenza' },
  { k: 'database', l: 'Database: data di creazione e dimensione' },
  { k: 'risorse', l: 'Risorse del server (CPU e memoria)' },
];
const CONTROLLI_PER_TECNOLOGIA = {
  server: [...CONTROLLI_COMUNI, { k: 'indici', l: 'Indici inutilizzati e tabelle senza chiave primaria' }],
  postgres: [...CONTROLLI_COMUNI, { k: 'indici', l: 'Indici inutilizzati e tabelle senza chiave primaria' },
    { k: 'transazioni_inattive', l: 'Transazioni inattive da oltre 5 minuti' }],
  sqlserver: [...CONTROLLI_COMUNI, { k: 'topologia', l: 'Topologia (standalone, FCI, Always On)' }],
};

/* Insight con IA: abilitazione, provider e chiavi API di Anthropic e OpenAI. Solo amministratore. */
function AiPanel() {
  const [stato, setStato] = useState(null);
  const [form, setForm] = useState({});
  const [errore, setErrore] = useState(null);
  const [msg, setMsg] = useState(null);
  const [risposta, setRisposta] = useState(null);
  const [busy, setBusy] = useState(false);

  const accetta = (s) => {
    setStato(s);
    setForm({
      abilitata: s.abilitata, provider: s.provider,
      modelloAnthropic: s.modelloAnthropic, modelloOpenai: s.modelloOpenai,
      chiaveAnthropic: '', chiaveOpenai: '',
      cancellaChiaveAnthropic: false, cancellaChiaveOpenai: false,
    });
  };

  const carica = useCallback(async () => {
    try { accetta(await api('/ai-config')); setErrore(null); } catch (e) { setErrore(e.message); }
  }, []);

  useEffect(() => { carica(); }, [carica]);

  if (!stato) return <p className="muted">{errore || 'Caricamento…'}</p>;

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));
  const modificato = form.abilitata !== stato.abilitata || form.provider !== stato.provider
    || form.modelloAnthropic !== stato.modelloAnthropic || form.modelloOpenai !== stato.modelloOpenai
    || form.chiaveAnthropic !== '' || form.chiaveOpenai !== ''
    || form.cancellaChiaveAnthropic || form.cancellaChiaveOpenai;
  // Con "entrambi" servono entrambe le chiavi
  const chiaveScelta = form.provider === 'entrambi' ? stato.anthropicConfigurata && stato.openaiConfigurata
    : form.provider === 'anthropic' ? stato.anthropicConfigurata : stato.openaiConfigurata;

  async function salva() {
    setBusy(true); setErrore(null); setMsg(null); setRisposta(null);
    try {
      accetta(await api('/ai-config', { method: 'PUT', body: form }));
      setMsg('Configurazione salvata');
    } catch (e) { setErrore(e.message); }
    setBusy(false);
  }

  async function provaConnessione() {
    setBusy(true); setErrore(null); setMsg(null); setRisposta(null);
    try { setRisposta((await api('/ai-config/prova', { method: 'POST' })).risposta); }
    catch (e) { setErrore(e.message); }
    setBusy(false);
  }

  const blocco = (nome, etichetta, configurata, mascherata, cancella) => (
    <div className="ai-key">
      <div>
        <strong>{etichetta}</strong>{' '}
        <span className="muted small">
          {cancella ? 'verrà rimossa al salvataggio' : configurata ? `configurata (${mascherata})` : 'non configurata'}
        </span>
      </div>
      <div className="ai-key-row">
        <input type="password" autoComplete="off" value={form[`chiave${nome}`]} disabled={busy || cancella}
          placeholder={configurata ? 'Incolla una nuova chiave per sostituirla' : 'Incolla la chiave API'}
          aria-label={`Chiave API ${etichetta}`}
          onChange={(e) => set(`chiave${nome}`, e.target.value)} />
        {configurata && (
          <button className="secondary" disabled={busy} onClick={() => set(`cancellaChiave${nome}`, !cancella)}>
            {cancella ? 'Annulla rimozione' : 'Rimuovi'}
          </button>
        )}
      </div>
    </div>
  );

  return (
    <>
      <div className="warn-banner" role="status">
        Con l'IA attiva, il report Insight viene inviato al provider scelto: nomi dei server, versioni, punteggi
        ed esiti dei controlli (possono comparire nomi di utenti e database). Non vengono inviati indirizzi di rete
        né password. Verificare i termini del provider prima di abilitarlo.
      </div>

      <Section title="Stato">
        <div className="menu subtabs" role="group" aria-label="Abilitazione analisi IA">
          <button className={form.abilitata ? 'menu-item active' : 'menu-item'} aria-pressed={form.abilitata}
            disabled={busy} onClick={() => set('abilitata', true)}>Abilita</button>
          <button className={!form.abilitata ? 'menu-item active' : 'menu-item'} aria-pressed={!form.abilitata}
            disabled={busy} onClick={() => set('abilitata', false)}>Disabilita</button>
        </div>
        <p className="muted small">
          {stato.abilitata ? 'Attiva: il prossimo report Insight includerà il commento dell\'IA.' : 'Disattiva: l\'Insight usa solo i controlli interni.'}
        </p>
      </Section>

      <Section title="Provider e modelli">
        <label>Provider
          <select value={form.provider} disabled={busy} onChange={(e) => set('provider', e.target.value)}>
            <option value="anthropic">Anthropic</option>
            <option value="openai">OpenAI</option>
            <option value="entrambi">Entrambi (Anthropic e OpenAI)</option>
          </select>
        </label>
        <p className="muted small">
          {form.provider === 'entrambi'
            ? 'Il commento IA riporta la risposta di entrambi i provider, uno sotto l\'altro. Servono entrambe le chiavi.'
            : 'Il commento IA usa solo il provider scelto.'}
        </p>
        <label>Modello Anthropic
          <input value={form.modelloAnthropic} disabled={busy} onChange={(e) => set('modelloAnthropic', e.target.value)} />
        </label>
        <label>Modello OpenAI
          <input value={form.modelloOpenai} disabled={busy} onChange={(e) => set('modelloOpenai', e.target.value)} />
        </label>
        <p className="muted small">Predefiniti: {stato.modelliPredefiniti.anthropic} e {stato.modelliPredefiniti.openai}.</p>
      </Section>

      <Section title="Chiavi API">
        {blocco('Anthropic', 'Anthropic', stato.anthropicConfigurata, stato.anthropicMascherata, form.cancellaChiaveAnthropic)}
        {blocco('Openai', 'OpenAI', stato.openaiConfigurata, stato.openaiMascherata, form.cancellaChiaveOpenai)}
        <p className="muted small">Le chiavi sono cifrate nel database e non vengono più mostrate per intero.</p>
      </Section>

      <div className="param-footer">
        {errore && <span className="bad-text">{errore}</span>}
        {msg && <span className="ok-text">{msg}</span>}
        {risposta && <span className="ok-text">Risposta del provider: {risposta}</span>}
        <button className="secondary" onClick={provaConnessione} disabled={busy || modificato || !chiaveScelta}
          title={modificato ? 'Salvare prima le modifiche' : !chiaveScelta ? 'Inserire la chiave del provider scelto' : ''}>
          Prova connessione
        </button>
        <button onClick={salva} disabled={busy || !modificato}>
          {modificato ? 'Salva configurazione' : 'Nessuna modifica'}
        </button>
      </div>
    </>
  );
}

/* Notifiche Telegram degli eventi del monitor. Solo amministratore. Il token non viene mai mostrato per intero. */
function TelegramPanel() {
  const [stato, setStato] = useState(null);
  const [form, setForm] = useState({});
  const [errore, setErrore] = useState(null);
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);

  const accetta = (s) => {
    setStato(s);
    setForm({ abilitata: s.abilitata, chatId: s.chatId, gravitaMinima: s.gravitaMinima, token: '', cancellaToken: false });
  };

  const carica = useCallback(async () => {
    try { accetta(await api('/telegram-config')); setErrore(null); } catch (e) { setErrore(e.message); }
  }, []);

  useEffect(() => { carica(); }, [carica]);

  if (!stato) return <p className="muted">{errore || 'Caricamento…'}</p>;

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));
  const modificato = form.abilitata !== stato.abilitata || form.chatId !== stato.chatId
    || form.gravitaMinima !== stato.gravitaMinima || form.token !== '' || form.cancellaToken;

  async function salva() {
    setBusy(true); setErrore(null); setMsg(null);
    try {
      accetta(await api('/telegram-config', { method: 'PUT', body: form }));
      setMsg('Configurazione salvata');
    } catch (e) { setErrore(e.message); }
    setBusy(false);
  }

  async function prova() {
    setBusy(true); setErrore(null); setMsg(null);
    try { setMsg((await api('/telegram-config/prova', { method: 'POST' })).risposta); }
    catch (e) { setErrore(e.message); }
    setBusy(false);
  }

  return (
    <>
      <Section title="Stato">
        <div className="menu subtabs" role="group" aria-label="Notifiche Telegram">
          <button className={form.abilitata ? 'menu-item active' : 'menu-item'} aria-pressed={form.abilitata}
            disabled={busy} onClick={() => set('abilitata', true)}>Abilita</button>
          <button className={!form.abilitata ? 'menu-item active' : 'menu-item'} aria-pressed={!form.abilitata}
            disabled={busy} onClick={() => set('abilitata', false)}>Disabilita</button>
        </div>
        <p className="muted small">
          {stato.abilitata ? 'Attive: gli eventi della gravità scelta vengono inviati su Telegram.' : 'Disattive: nessun evento viene inviato.'}
        </p>
      </Section>

      <Section title="Bot e destinazione">
        <label>Token del bot
          <input type="password" autoComplete="off" value={form.token || ''} disabled={busy || form.cancellaToken}
            placeholder={stato.tokenConfigurato
              ? `Configurato (${stato.tokenMascherato}): incolla un nuovo token per sostituirlo`
              : 'Incolla il token ricevuto da BotFather'}
            aria-label="Token del bot Telegram"
            onChange={(e) => set('token', e.target.value)} />
        </label>
        {stato.tokenConfigurato && (
          <button className="secondary" disabled={busy} onClick={() => set('cancellaToken', !form.cancellaToken)}>
            {form.cancellaToken ? 'Annulla rimozione' : 'Rimuovi token'}
          </button>
        )}
        <label>Chat ID
          <input value={form.chatId || ''} disabled={busy} placeholder="-1001234567890 oppure @nomecanale"
            onChange={(e) => set('chatId', e.target.value)} />
        </label>
        <p className="muted small">Il token è cifrato nel database. Il chat ID è il gruppo o il canale che riceve le notifiche.</p>
      </Section>

      <Section title="Eventi da notificare">
        <label>Gravità minima
          <select value={form.gravitaMinima || 'CRITICAL'} disabled={busy} onChange={(e) => set('gravitaMinima', e.target.value)}>
            <option value="CRITICAL">Solo critici</option>
            <option value="WARNING">Critici e avvisi</option>
            <option value="INFO">Tutti, anche informativi</option>
          </select>
        </label>
        <p className="muted small">
          Ogni notifica ha il pulsante "Presa visione". Finché non viene confermata, lo stesso evento non viene inviato di nuovo.
          Notifiche in attesa di conferma: <strong>{stato.inAttesa ?? 0}</strong>.
        </p>
      </Section>

      <div className="param-footer">
        {errore && <span className="bad-text">{errore}</span>}
        {msg && <span className="ok-text">{msg}</span>}
        <button className="secondary" onClick={prova}
          disabled={busy || modificato || !stato.tokenConfigurato || !stato.chatId}
          title={modificato ? 'Salvare prima le modifiche' : (!stato.tokenConfigurato || !stato.chatId) ? 'Inserire token e chat ID' : ''}>
          Invia messaggio di prova
        </button>
        <button onClick={salva} disabled={busy || !modificato}>
          {modificato ? 'Salva configurazione' : 'Nessuna modifica'}
        </button>
      </div>
    </>
  );
}

/* Parametri di rilevamento: tutti li vedono, solo l'amministratore li modifica. */
function ParametriPanel({ puoModificare }) {
  const [elenco, setElenco] = useState(null);
  const [valori, setValori] = useState({});
  const [errore, setErrore] = useState(null);
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);

  const accetta = (lista) => {
    setElenco(lista);
    setValori(Object.fromEntries(lista.map((p) => [p.chiave, String(p.valore)])));
  };

  const carica = useCallback(async () => {
    try { accetta(await api('/impostazioni')); setErrore(null); } catch (e) { setErrore(e.message); }
  }, []);

  useEffect(() => { carica(); }, [carica]);

  if (!elenco) return <p className="muted">{errore || 'Caricamento…'}</p>;

  const modificati = elenco.filter((p) => valori[p.chiave] !== String(p.valore));
  const gruppi = [...new Set(elenco.map((p) => p.gruppo))];

  async function salva() {
    setBusy(true); setErrore(null); setMsg(null);
    try {
      const body = Object.fromEntries(modificati.map((p) => [p.chiave, valori[p.chiave]]));
      accetta(await api('/impostazioni', { method: 'PUT', body }));
      setMsg('Parametri salvati: si applicano dal prossimo ciclo di rilevamento');
    } catch (e) { setErrore(e.message); }
    setBusy(false);
  }

  async function ripristina(p) {
    setBusy(true); setErrore(null); setMsg(null);
    try { accetta(await api('/impostazioni/ripristina', { method: 'POST', body: { chiave: p.chiave } })); }
    catch (e) { setErrore(e.message); }
    setBusy(false);
  }

  return (
    <>
      {!puoModificare && <div className="warn-banner" role="status">Sola lettura: solo l'amministratore può modificare questi parametri.</div>}
      {gruppi.map((g) => (
        <Section key={g} title={g}>
          <div className="table-wrap">
            <table className="param-table">
              <thead><tr><th>Parametro</th><th>Valore</th><th>Predefinito</th><th>Intervallo</th><th></th></tr></thead>
              <tbody>
                {elenco.filter((p) => p.gruppo === g).map((p) => (
                  <tr key={p.chiave}>
                    <td>
                      <strong>{p.descrizione}</strong>
                      <div className="muted small"><code>{p.chiave}</code></div>
                    </td>
                    <td>
                      <span className="param-input">
                        <input type="number" min={p.min} max={p.max} value={valori[p.chiave] ?? ''} disabled={!puoModificare || busy}
                          aria-label={p.descrizione}
                          onChange={(e) => setValori({ ...valori, [p.chiave]: e.target.value })} />
                        <span className="muted small">{p.unita}</span>
                      </span>
                    </td>
                    <td>{p.predefinito} {p.unita}</td>
                    <td className="muted small">{p.min}–{p.max} {p.unita}</td>
                    <td>
                      {puoModificare && p.valore !== p.predefinito && (
                        <button className="secondary" disabled={busy} onClick={() => ripristina(p)}>Predefinito</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>
      ))}
      {puoModificare && (
        <div className="param-footer">
          {errore && <span className="bad-text">{errore}</span>}
          {msg && <span className="ok-text">{msg}</span>}
          <button onClick={salva} disabled={busy || modificati.length === 0}>
            {modificati.length > 0 ? `Salva ${modificati.length} modific${modificati.length > 1 ? 'he' : 'a'}` : 'Nessuna modifica'}
          </button>
        </div>
      )}
    </>
  );
}

/* Anagrafica dei siti (Roma, Viterbo, AWS cloud...): creazione, modifica, eliminazione. Solo amministratore. */
function SitiPanel() {
  const [siti, setSiti] = useState(null);
  const [form, setForm] = useState({ id: '', nome: '', descrizione: '' });
  const [errore, setErrore] = useState(null);
  const [daEliminare, setDaEliminare] = useState(null);

  const carica = useCallback(async () => {
    try { setSiti(await api('/siti')); setErrore(null); } catch (e) { setErrore(e.message); }
  }, []);
  useEffect(() => { carica(); }, [carica]);

  async function salva(e) {
    e.preventDefault();
    setErrore(null);
    const body = { nome: form.nome, descrizione: form.descrizione };
    try {
      const lista = form.id
        ? await api(`/siti/${form.id}`, { method: 'PUT', body })
        : await api('/siti', { method: 'POST', body });
      setSiti(lista);
      setForm({ id: '', nome: '', descrizione: '' });
    } catch (err) { setErrore(err.message); }
  }

  async function elimina() {
    const s = daEliminare;
    setDaEliminare(null);
    try { setSiti(await api(`/siti/${s.id}`, { method: 'DELETE' })); setErrore(null); }
    catch (err) { setErrore(err.message); }
  }

  return (
    <>
      <Section title={form.id ? 'Modifica sito' : 'Nuovo sito'}>
        <form className="sito-form" onSubmit={salva}>
          <label>Nome<input value={form.nome} onChange={(e) => setForm({ ...form, nome: e.target.value })}
            placeholder="es. Roma, Viterbo, AWS cloud" maxLength={64} required /></label>
          <label>Descrizione (facoltativa)<input value={form.descrizione} onChange={(e) => setForm({ ...form, descrizione: e.target.value })} maxLength={255} /></label>
          <div className="modal-actions">
            {form.id && <button type="button" className="secondary" onClick={() => setForm({ id: '', nome: '', descrizione: '' })}>Annulla</button>}
            <button type="submit">{form.id ? 'Salva modifiche' : 'Crea sito'}</button>
          </div>
        </form>
        {errore && <div className="login-error" role="alert">{errore}</div>}
      </Section>
      <Section title="Siti">
        {siti === null && <p className="muted">Caricamento…</p>}
        {siti && siti.length === 0 && <p className="muted">Nessun sito: crearne uno per assegnarlo ai server.</p>}
        {siti && siti.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Sito</th><th>Descrizione</th><th>Server</th><th>Azioni</th></tr></thead>
              <tbody>
                {siti.map((x) => (
                  <tr key={x.id}>
                    <td><strong>{x.nome}</strong></td>
                    <td className="muted">{x.descrizione || '—'}</td>
                    <td>{x.server}</td>
                    <td className="actions">
                      <button className="secondary" onClick={() => setForm({ id: x.id, nome: x.nome, descrizione: x.descrizione })}>Modifica</button>
                      <button className="danger" onClick={() => setDaEliminare(x)}>Elimina</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>
      {daEliminare && (
        <ConfirmDialog messaggio={`Eliminare il sito "${daEliminare.nome}"?`} onConfirm={elimina} onCancel={() => setDaEliminare(null)} />
      )}
    </>
  );
}

/* Configurazione: sotto-aree server da monitorare e utenti. */
/* Utente non amministratore: nella configurazione può solo reimpostare la propria password. */
function PasswordPropria() {
  const [aperto, setAperto] = useState(false);
  return (
    <section className="card">
      <div className="card-head">
        <h3>La mia password</h3>
        <button className="secondary" onClick={() => setAperto(true)}>Reset della mia password</button>
      </div>
      <p className="muted">Per cambiare la password serve quella attuale. Dopo il cambio restano aperte le sessioni in corso.</p>
      {aperto && <ChangePasswordDialog onClose={() => setAperto(false)} />}
    </section>
  );
}

function ConfigPage({ me, permessi, onChanged }) {
  const amministra = permessi.includes('configurazione');
  const [area, setArea] = useState('parametri');
  if (!amministra) return <PasswordPropria />;
  const aree = [
    { id: 'parametri', label: 'Parametri di rilevamento', visibile: true },
    { id: 'siti', label: 'Siti', visibile: amministra },
    { id: 'servers', label: 'Server da monitorare', visibile: amministra },
    { id: 'ia', label: 'Insight IA', visibile: amministra },
    { id: 'telegram', label: 'Telegram', visibile: amministra },
    { id: 'variabili', label: 'Variabili di configurazione', visibile: amministra },
    { id: 'users', label: 'Utenti', visibile: amministra },
  ].filter((x) => x.visibile);
  const corrente = aree.some((x) => x.id === area) ? area : 'parametri';
  // Esportazione completa (server, utenti, parametri), scaricata come file JSON
  const esporta = () => { window.location.href = '/api/esportazione'; };
  return (
    <>
      <div className="config-bar">
        <nav className="menu subtabs" aria-label="Aree di configurazione">
          {aree.map((x) => (
            <button key={x.id} className={corrente === x.id ? 'menu-item active' : 'menu-item'} onClick={() => setArea(x.id)}>{x.label}</button>
          ))}
        </nav>
        <button className="secondary" onClick={esporta}>Esporta configurazione</button>
      </div>
      {corrente === 'parametri' && <ParametriPanel puoModificare={amministra} />}
      {corrente === 'siti' && <SitiPanel />}
      {corrente === 'servers' && <ConfigTab onChanged={onChanged} />}
      {corrente === 'ia' && <AiPanel />}
      {corrente === 'telegram' && <TelegramPanel />}
      {corrente === 'variabili' && <VariabiliPanel />}
      {corrente === 'users' && <UsersTab me={me} />}
    </>
  );
}

/* ------------------------------------------------------------ INSIGHT: health check giornaliero */

const LIVELLI_INSIGHT = { bad: 'Critico', warn: 'Attenzione', info: 'Info', ok: 'OK' };

/* Anello del punteggio di salute di un server (0-100). */
function PunteggioAnello({ valore }) {
  const r = 26;
  const c = 2 * Math.PI * r;
  const colore = valore >= 85 ? '#22c55e' : valore >= 60 ? '#f59e0b' : '#ef4444';
  return (
    <svg className="score-ring" width="66" height="66" viewBox="0 0 66 66" role="img" aria-label={`Punteggio ${valore} su 100`}>
      <circle cx="33" cy="33" r={r} fill="none" stroke="#132340" strokeWidth="7" />
      <circle cx="33" cy="33" r={r} fill="none" stroke={colore} strokeWidth="7" strokeLinecap="round"
        strokeDasharray={`${(valore / 100) * c} ${c}`} transform="rotate(-90 33 33)" />
      <text x="33" y="38" textAnchor="middle" fontSize="16" fontWeight="800" fill="#e6edf8">{valore}</text>
    </svg>
  );
}

function InsightPage({ permessi }) {
  const [report, setReport] = useState(null);
  const [caricato, setCaricato] = useState(false);
  const [generando, setGenerando] = useState(false);
  const [error, setError] = useState(null);

  const carica = useCallback(async () => {
    try { setReport(await api('/insights')); setError(null); } catch (e) { setError(e.message); }
    setCaricato(true);
  }, []);

  useEffect(() => { carica(); }, [carica]);

  async function genera() {
    setGenerando(true); setError(null);
    try { setReport(await api('/insights/genera', { method: 'POST' })); } catch (e) { setError(e.message); }
    setGenerando(false);
  }

  if (!caricato) return <p className="muted">Caricamento…</p>;
  if (error && !report) return <p className="bad-text banner">{error}</p>;
  if (!report || report.nessunReport) {
    return (
      <section className="card insight-empty">
        <h3>Nessun report ancora</h3>
        <p className="muted">Il primo report viene generato automaticamente, poi ogni giorno alle 06:00.</p>
        {permessi.includes('diagnostica') && <button onClick={genera} disabled={generando}>{generando ? 'Genero…' : 'Genera ora'}</button>}
      </section>
    );
  }

  if (report.server.length === 0) {
    return (
      <section className="card insight-empty">
        <h3>Nessun server nel report</h3>
        <p className="muted">Nessun server ha l'opzione "Includi nel report Insight" attiva. Attivala nella configurazione del server, in Server da monitorare.</p>
      </section>
    );
  }

  const tot = { bad: 0, warn: 0, info: 0, ok: 0 };
  report.server.forEach((sv) => Object.entries(sv.conteggi).forEach(([k, v]) => { tot[k] = (tot[k] || 0) + v; }));

  return (
    <>
      {report.nonAnalizzati && (
        <p className="bad-text banner" role="alert">
          Server non analizzati in questo report: {report.nonAnalizzati.join(' · ')}
        </p>
      )}
      <section className="card insight-head">
        <div className="insight-dates">
          <div><span className="kpi-label">Report generato il</span><strong>{fmtData(report.generatoIl)}</strong></div>
          <div><span className="kpi-label">Prossima produzione</span><strong>{fmtData(report.prossimo)}</strong></div>
        </div>
        <div className="insight-actions">
          {error && <span className="bad-text small">{error}</span>}
          {permessi.includes('diagnostica') && (
            <button className="secondary" onClick={genera} disabled={generando}>{generando ? 'Genero…' : 'Genera ora'}</button>
          )}
        </div>
      </section>

      <div className="kpis insight-kpis">
        <div className="kpi crit"><span className="kpi-label">Critici</span><strong>{tot.bad}</strong></div>
        <div className="kpi warn"><span className="kpi-label">Attenzione</span><strong>{tot.warn}</strong></div>
        <div className="kpi"><span className="kpi-label">Info</span><strong>{tot.info}</strong></div>
        <div className="kpi info"><span className="kpi-label">OK</span><strong>{tot.ok}</strong></div>
      </div>

      {report.analisiIA && (
        <section className="card insight-ia">
          <h3>Commento IA{report.analisiIAProvider && <span className="muted small"> · {report.analisiIAProvider}</span>}</h3>
          <div className="ai-text">{report.analisiIA}</div>
        </section>
      )}
      {!report.analisiIA && report.analisiIAErrore && (
        <p className="muted small">Commento IA non disponibile: {report.analisiIAErrore}</p>
      )}

      {report.server.length === 0 && <p className="muted">Nessun server MySQL o MariaDB visibile.</p>}

      {report.server.map((sv) => (
        <section key={sv.id} className="card insight-server">
          <div className="insight-server-head">
            <PunteggioAnello valore={sv.punteggio} />
            <div>
              <h3>{sv.nome}</h3>
              <div className="muted small">{sv.motore || 'motore n/d'} {sv.versione}</div>
              <div className="insight-counts">
                {['bad', 'warn', 'info', 'ok'].map((l) => (
                  <span key={l} className={`plan-chip ${l === 'bad' ? 'bad' : l === 'ok' ? 'ok' : ''}`}>{LIVELLI_INSIGHT[l]} {sv.conteggi[l] || 0}</span>
                ))}
              </div>
            </div>
          </div>
          <ul className="insight-list">
            {sv.controlli.map((c, i) => (
              <li key={i} className={`insight-item ${c.livello}`}>
                <span className={`plan-badge ${c.livello === 'bad' ? 'bad' : c.livello === 'warn' ? 'warn' : c.livello === 'ok' ? 'ok' : ''}`}>{LIVELLI_INSIGHT[c.livello]}</span>
                <div className="insight-body">
                  <strong>{c.controllo}</strong> <span className="muted">— {c.esito}</span>
                  {c.raccomandazione && <div className="insight-rec">{c.raccomandazione}</div>}
                  {c.riferimento && <a href={c.riferimento} target="_blank" rel="noreferrer" className="small">riferimento ↗</a>}
                </div>
              </li>
            ))}
          </ul>
        </section>
      ))}

      <section className="card">
        <h4>Fonti delle raccomandazioni</h4>
        <ul className="insight-fonti">
          {report.fonti.map((f) => (
            <li key={f.url}><a href={f.url} target="_blank" rel="noreferrer">{f.titolo} ↗</a></li>
          ))}
        </ul>
      </section>
    </>
  );
}

/* Ricerca di un database su tutti i server dell'anagrafica visibili all'utente. */
function RicercaPage() {
  const [testo, setTesto] = useState('');
  const [esito, setEsito] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function cerca(e) {
    e.preventDefault();
    setBusy(true); setError(null);
    try { setEsito(await api(`/ricerca?q=${encodeURIComponent(testo.trim())}`)); }
    catch (err) { setError(err.message); setEsito(null); }
    setBusy(false);
  }

  return (
    <>
      <section className="card search-card">
        <h3>Cerca un database</h3>
        <p className="muted">Cerca in tutti i server MySQL e MariaDB dell'anagrafica che puoi vedere. Almeno 2 caratteri, senza distinguere le maiuscole.</p>
        <form className="search-form" onSubmit={cerca} role="search">
          <input type="search" value={testo} onChange={(e) => setTesto(e.target.value)} placeholder="nome del database, es. testdb" aria-label="Nome del database" autoFocus />
          <button type="submit" disabled={busy || testo.trim().length < 2}>{busy ? 'Cerco…' : 'Cerca'}</button>
        </form>
        {error && <p className="bad-text">{error}</p>}
      </section>

      {esito && (
        <section className="card">
          <div className="card-head">
            <h3>{esito.totale === 0 ? 'Nessun database trovato' : `${esito.totale} database trovati`}</h3>
            <span className="muted small">su {esito.serverInterrogati} server interrogati{esito.troncati ? ' · risultati troncati' : ''}</span>
          </div>
          {esito.risultati.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Database</th><th>Server</th><th>Ambiente</th><th>Sito</th><th>Host</th><th>Motore</th></tr></thead>
                <tbody>
                  {esito.risultati.map((r, i) => (
                    <tr key={`${r.serverId}-${r.database}-${i}`}>
                      <td><strong>{r.database}</strong></td>
                      <td>{r.server}</td>
                      <td><AmbienteBadge valore={r.ambiente} /></td>
                      <td>{r.sito || '—'}</td>
                      <td><code>{r.host}</code></td>
                      <td>{r.motore}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {esito.nonRaggiungibili.length > 0 && (
            <div className="warn-banner" role="status">
              Server non interrogati: {esito.nonRaggiungibili.map((n) => `${n.nome} (${n.errore})`).join(' · ')}
            </div>
          )}
        </section>
      )}
    </>
  );
}

/* Manuale del programma: scheda dentro l'applicazione, visibile a tutti gli utenti. */
const MANUALE = [
  {
    id: 'introduzione', titolo: 'Introduzione',
    paragrafi: [
      'DBGuardian è un monitor per database MySQL, MariaDB e PostgreSQL. Raccoglie lo stato dei server in sola lettura, mostra cluster, repliche e singoli server, registra gli eventi e produce un health check giornaliero.',
      'Il programma non modifica i database monitorati. Le uniche azioni che cambiano stato sono quelle di configurazione dell\'anagrafica e dei parametri, riservate all\'amministratore.',
    ],
  },
  {
    id: 'accesso', titolo: 'Accesso e ruoli',
    paragrafi: [
      'Si accede con username e password. In alto a destra il menu utente permette di cambiare la propria password e di uscire. La sessione scade dopo 8 ore di inattività.',
    ],
    elenco: [
      'Amministratore: configura server, siti, utenti e parametri di rilevamento, e vede tutti i server.',
      'Utente: vede i server che gli sono stati assegnati, può eseguire test di connessione e rilevamenti forzati, e cambiare la propria password. Non può modificare la configurazione.',
      'Ci possono essere più amministratori. Il sistema non permette di eliminare l\'ultimo.',
    ],
  },
  {
    id: 'dashboard', titolo: 'Dashboard di monitoraggio',
    paragrafi: [
      'La dashboard mostra lo stato di tutti i server visibili. In alto ci sono i contatori per stato (server, OK, attenzione, critici, in attesa). A sinistra compaiono monitor, cluster, repliche e server singoli, ciascuno con un esagono colorato: il bordo indica lo stato, il simbolo al centro lo riassume.',
      'A destra compaiono i problemi da controllare e gli eventi recenti. Su schermi stretti le colonne vanno una sotto l\'altra.',
      'I filtri in alto restringono la vista per sito e per ambiente. "Rileva ora" avvia subito un rilevamento completo (disponibile per utenti e amministratori).',
    ],
    elenco: [
      'Verde: server in ordine. Giallo: attenzione (per esempio ritardo di replica, transazioni errant). Rosso: critico o non raggiungibile. Grigio: in attesa del primo rilevamento.',
      'Cliccando un server si apre il suo report. Sotto il nome compare il sito di riferimento, se assegnato.',
    ],
  },
  {
    id: 'rilevamento', titolo: 'Come avviene il rilevamento',
    paragrafi: [
      'Il monitor controlla ogni pochi secondi se ogni server risponde: un cambio di stato (un server che va giù o torna su) viene registrato subito. Lo stato generale (connessione, replica, cluster, query attive) viene rilevato a intervalli più lunghi, e le sezioni pesanti (indici, utenti, dimensione dei dati) a intervalli ancora più lunghi.',
      'Gli intervalli sono nei parametri di rilevamento. Le modifiche si applicano dal ciclo successivo. Nella pagina Help sono riportati i valori correnti.',
    ],
  },
  {
    id: 'topologia', titolo: 'Topologia e raggruppamenti',
    paragrafi: [
      'I cluster InnoDB e Galera sono raggruppati in un unico insieme con i loro membri. Le repliche asincrone e in streaming (MySQL o PostgreSQL) sono mostrate sotto il loro primario o sorgente.',
      'Per i cluster Galera, il monitor indica il nodo candidato al ripristino quando il cluster è fermo, leggendo lo stato salvato di ciascun nodo. Quando la scelta non è sicura, lo segnala come provvisoria.',
      'Nella vista di dettaglio ogni nodo mostra nome, sito, ruolo, versione, ritardo di replica e dati.',
    ],
  },
  {
    id: 'insight', titolo: 'Insight',
    paragrafi: [
      'Insight è un health check giornaliero di MySQL, MariaDB e PostgreSQL. Per ogni server calcola un punteggio da 0 a 100 e un elenco di controlli con esito, raccomandazione e fonte. Il report include solo i server con l\'opzione "Includi nel report Insight" attiva nella configurazione del server.',
      'Il report viene generato ogni giorno all\'ora impostata nei parametri, e si può rigenerare con "Genera ora". La pagina mostra la data di generazione e quella della prossima produzione.',
    ],
    elenco: [
      'Controlli comuni: disponibilità, versione (con fine supporto), replica, cluster, chiavi primarie, indici inutilizzati, utenti, query lente e lunghe.',
      'Controlli di configurazione MySQL/MariaDB: connessioni, buffer pool, tabelle temporanee, binary log, durabilità, slow query log, sql_mode, local_infile.',
      'Controlli PostgreSQL: WAL, archiviazione WAL, cifratura delle password, connessioni, cache, transazioni inattive, log delle query lente.',
      'Un server non raggiungibile ha punteggio 0.',
    ],
  },
  {
    id: 'insight-ia', titolo: 'Insight con IA (amministratore)',
    paragrafi: [
      'Se abilitato, il report Insight include un commento generato da un modello di intelligenza artificiale. Il provider si sceglie nella pagina "Insight IA": Anthropic, OpenAI oppure entrambi. Con entrambi, il commento riporta la risposta di ciascun provider, uno sotto l\'altro. Ogni provider richiede la propria chiave API, inserita una sola volta e poi cifrata nel database.',
      'Ogni modello si indica per nome nella stessa pagina (per esempio un modello Sonnet di Anthropic, gpt-4.1-mini per OpenAI). Il pulsante "Prova connessione" verifica le chiavi e i modelli inviando una richiesta di prova: va usato dopo il salvataggio.',
      'L\'intestazione del commento riporta il provider e il modello che lo hanno prodotto. Se il provider non risponde, il report viene comunque generato, e l\'errore compare al posto del commento.',
    ],
    elenco: [
      'Dati inviati: per ogni server incluso nel report, il nome in anagrafica, il motore e la versione, il punteggio, e per ogni controllo il nome, il livello, l\'esito e la raccomandazione. Più le istruzioni fisse per il modello.',
      'Dati non inviati: indirizzi host, porte, utenti, password e stringhe di connessione dei server.',
      'Gli esiti e le raccomandazioni possono contenere nomi di utenti, database o tabelle: vengono inviati così come sono prodotti dai controlli.',
      'Con l\'IA disabilitata, il report usa solo i controlli interni e non invia nulla a terzi.',
    ],
  },
  {
    id: 'ricerca', titolo: 'Ricerca',
    paragrafi: [
      'La ricerca cerca un database per nome su tutti i server MySQL, MariaDB e PostgreSQL visibili. Richiede almeno 2 caratteri e non distingue maiuscole e minuscole.',
      'Il risultato indica server, sito e ambiente. I server che non rispondono sono elencati a parte.',
    ],
  },
  {
    id: 'report', titolo: 'Report e piano di esecuzione',
    paragrafi: [
      'Il report di un server contiene identità e versione, topologia, membri del cluster, stato della replica, risorse, query attive e più lente, indici e utenti.',
      'Cliccando una query attiva o una query lenta si apre il piano di esecuzione (EXPLAIN), mostrato come grafico: scansioni, ricerche su indice, ordinamenti e costo di ciascun passo. La query non viene eseguita. Per le query del digest, che hanno valori segnaposto, il piano è una stima; i testi troncati non si possono spiegare.',
    ],
  },
  {
    id: 'configurazione', titolo: 'Configurazione (amministratore)',
    paragrafi: [
      'Parametri di rilevamento: frequenze dei controlli, limiti delle query, giorni di conservazione dei dati, ora di Insight. Ogni parametro ha un intervallo ammesso e si riporta al predefinito con un clic.',
      'Siti: l\'anagrafica dei siti in cui si trovano i server (per esempio Roma, Viterbo, AWS cloud). Un sito in uso non si può eliminare; rinominandolo, il nuovo nome va a tutti i server che lo usano.',
      'Server da monitorare: l\'anagrafica. Per ogni server si indicano nome, tipo (MySQL/MariaDB, PostgreSQL, router, database del monitor), host, porta, utente, ambiente (produzione, certificazione, collaudo o non definito), sito e cluster Galera se presente. La password viene salvata cifrata e non viene più mostrata. Il pulsante di test verifica la connessione.',
      'Includi nel report Insight: attiva o disattiva il server nel report giornaliero e nella pagina Insight. Un server escluso non viene analizzato e non compare nel commento IA. Per router e database del monitor l\'opzione non è disponibile.',
      'Utenti: creazione, ruolo, server assegnati, reset della password di un altro utente, eliminazione.',
      'Esporta configurazione: scarica un file JSON con server, utenti e parametri. Le password non sono incluse.',
    ],
  },
  {
    id: 'password', titolo: 'Password e sicurezza',
    paragrafi: [
      'Ogni utente può cambiare la propria password dal menu utente o dalla sezione Configurazione; il cambio richiede la password attuale. L\'amministratore può reimpostare la password di un altro utente: le sue sessioni aperte si chiudono.',
      'Le password dei server monitorati sono cifrate con una chiave che sta nella configurazione del container (variabile APP_SECRET_KEY) e non nel database. Se la chiave si perde, le password salvate non si possono più leggere: va conservata una copia sicura.',
    ],
  },
  {
    id: 'frequenze', titolo: 'Frequenze di rilevamento',
    paragrafi: [
      'I valori correnti delle frequenze, dei limiti e della conservazione sono riportati nella pagina Help, letti dal database a ogni apertura. Se l\'amministratore li cambia, la pagina mostra i nuovi valori.',
    ],
  },
  {
    id: 'risoluzione', titolo: 'Risoluzione dei problemi',
    elenco: [
      'La dashboard è vuota: controllare i filtri Sito e Ambiente in alto, e che l\'utente abbia i server assegnati.',
      'Un server risulta giù: verificare che sia acceso e raggiungibile, e che utente e password nell\'anagrafica siano corretti (pulsante di test).',
      'Un cluster Galera è fermo: il monitor indica il nodo candidato. Il ripristino va eseguito con le procedure del cluster, non dal monitor.',
      'I dati sembrano vecchi: "Rileva ora" avvia un rilevamento completo. La data di aggiornamento è in cima alla dashboard.',
      'Le password non funzionano dopo una reinstallazione: la chiave APP_SECRET_KEY deve essere la stessa usata per salvarle.',
    ],
  },
];

function ManualePage() {
  return (
    <div className="manuale">
      <nav className="manuale-indice card" aria-label="Indice del manuale">
        <h4>Indice</h4>
        <ol>
          {MANUALE.map((x) => <li key={x.id}><a href={`#manuale-${x.id}`}>{x.titolo}</a></li>)}
        </ol>
      </nav>
      <div className="manuale-testo">
        {MANUALE.map((x) => (
          <section key={x.id} id={`manuale-${x.id}`} className="card manuale-sezione">
            <h3>{x.titolo}</h3>
            {(x.paragrafi || []).map((t, i) => <p key={i}>{t}</p>)}
            {x.elenco && (
              <ul>{x.elenco.map((t, i) => <li key={i}>{t}</li>)}</ul>
            )}
          </section>
        ))}
      </div>
    </div>
  );
}

/* About: nome del prodotto, versione e autore. */
function AboutPage() {
  return (
    <div className="about">
      <section className="card about-prodotto">
        <h2>DBGuardian</h2>
        <p><strong>Database Monitor Tool</strong></p>
        <p className="muted">Agent Free · Basic · Alfa</p>
        <p>
          Monitoraggio di MySQL, MariaDB, PostgreSQL e SQL Server senza installare agenti sui server:
          tutto avviene via JDBC, in sola lettura.
        </p>
      </section>
      <section className="card about-autore">
        <h3>Chi l'ha realizzato</h3>
        <p><strong>Quirino Scaramastra</strong></p>
        <p>Sito personale: <a href="https://mdsc.it" target="_blank" rel="noopener noreferrer">mdsc.it</a></p>
      </section>
      <section className="card about-licenza">
        <h3>Licenza</h3>
        <p>
          DBGuardian è distribuito con licenza <strong>Apache License, Version 2.0</strong>.
          Copyright 2026 Quirino Scaramastra.
        </p>
        <p>
          Chiunque può usare, studiare, modificare e distribuire il software, anche a fini commerciali.
          Chi lo distribuisce deve includere la licenza e il file NOTICE, e conservare le note di copyright.
          La licenza include una concessione sui brevetti dei contributori e non concede diritti sul nome DBGuardian.
        </p>
        <p className="muted small">Testo completo: file LICENSE e NOTICE nella cartella del progetto.</p>
      </section>
    </div>
  );
}

const PAGES = [
  { id: 'dashboard', label: 'Dashboard di monitoraggio' },
  { id: 'ricerca', label: 'Ricerca', permesso: 'monitoraggio' },
  { id: 'insight', label: 'Insight', permesso: 'monitoraggio' },
  { id: 'statistiche', label: 'Indici', permesso: 'monitoraggio' },
  { id: 'config', label: 'Configurazione', permesso: 'monitoraggio' },
  { id: 'report', label: 'Report' },
  { id: 'help', label: 'Help' },
  { id: 'manuale', label: 'Manuale' },
  { id: 'about', label: 'About' },
];

/* Pagina corrente letta dall'indirizzo (#/config ecc.), così il tasto indietro del browser funziona. */
function pageFromHash() {
  const id = window.location.hash.replace(/^#\/?/, '');
  return PAGES.some((p) => p.id === id) ? id : 'dashboard';
}

function usePage() {
  const [page, setPage] = useState(pageFromHash);
  useEffect(() => {
    const onChange = () => setPage(pageFromHash());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  const go = (id) => { window.location.hash = '/' + id; };
  return [page, go];
}

/* Maschera di accesso: username e password, nessun dato precompilato. */
function LoginPage({ onLogin }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const data = await api('/login', { method: 'POST', body: { username, password } });
      onLogin(data);
    } catch (err) {
      setError(err.message);
      setPassword('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand"><Brand /></div>
        <label className="field">
          <span>Username</span>
          <input autoFocus autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} />
        </label>
        <label className="field">
          <span>Password</span>
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error && <div className="login-error" role="alert">{error}</div>}
        <button type="submit" className="login-submit" disabled={busy || !username || !password}>
          {busy ? 'Accesso in corso…' : 'Accedi'}
        </button>
      </form>
    </div>
  );
}

/* Cambio password dell'utente collegato. */
function ChangePasswordDialog({ onClose }) {
  useEscape(onClose);
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [done, setDone] = useState(false);

  async function save(e) {
    e.preventDefault();
    setError('');
    if (next !== confirm) {
      setError('La nuova password e la conferma non coincidono');
      return;
    }
    try {
      await api('/password', { method: 'POST', body: { old: current, new: next } });
      setDone(true);
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <form className="modal" onMouseDown={(e) => e.stopPropagation()} onSubmit={save}>
        <h3>Cambia password</h3>
        {done ? (
          <>
            <p className="ok-text">Password aggiornata.</p>
            <div className="modal-actions"><button type="button" onClick={onClose}>Chiudi</button></div>
          </>
        ) : (
          <>
            <label className="field"><span>Password attuale</span>
              <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} /></label>
            <label className="field"><span>Nuova password (almeno 6 caratteri)</span>
              <input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} /></label>
            <label className="field"><span>Conferma nuova password</span>
              <input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} /></label>
            {error && <div className="login-error" role="alert">{error}</div>}
            <div className="modal-actions">
              <button type="button" className="secondary" onClick={onClose}>Annulla</button>
              <button type="submit" disabled={!current || !next || !confirm}>Salva</button>
            </div>
          </>
        )}
      </form>
    </div>
  );
}

/* Menu utente: nome, cambio password, uscita. Si chiude cliccando fuori. */
function UserMenu({ user, onLogout }) {
  const [open, setOpen] = useState(false);
  const [changing, setChanging] = useState(false);
  const ref = React.useRef(null);

  useEffect(() => {
    const outside = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', outside);
    return () => document.removeEventListener('mousedown', outside);
  }, []);

  async function logout() {
    setOpen(false);
    try { await api('/logout', { method: 'POST' }); } catch { /* sessione già chiusa */ }
    onLogout();
  }

  return (
    <div className="user-menu" ref={ref}>
      <button className="user-chip" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <span className="user-avatar">{user.charAt(0).toUpperCase()}</span>
        <span>{user}</span>
        <span className="caret" aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="user-dropdown" role="menu">
          <button role="menuitem" onClick={() => { setOpen(false); setChanging(true); }}>Cambia password</button>
          <button role="menuitem" className="danger" onClick={logout}>Esci</button>
        </div>
      )}
      {changing && <ChangePasswordDialog onClose={() => setChanging(false)} />}
    </div>
  );
}

/* Interfaccia dopo l'accesso: intestazione con utente, menu di navigazione, pagina corrente. */
function Console({ user, permessi, onLogout }) {
  const [page, go] = usePage();
  const [refresh, setRefresh] = useState(0);
  // Le pagine che richiedono un permesso non compaiono per gli utenti che non lo hanno
  const voci = PAGES.filter((p) => !p.permesso || permessi.includes(p.permesso));
  const corrente = voci.some((p) => p.id === page) ? page : 'dashboard';

  return (
    <PermessiContext.Provider value={permessi}>
    <div className="app">
      <div className="topbar">
        <Brand />
        <UserMenu user={user} onLogout={onLogout} />
      </div>
      <nav className="menu" aria-label="Navigazione principale">
        {voci.map((p) => (
          <button
            key={p.id}
            className={corrente === p.id ? 'menu-item active' : 'menu-item'}
            aria-current={corrente === p.id ? 'page' : undefined}
            onClick={() => go(p.id)}
          >
            {p.label}
          </button>
        ))}
      </nav>
      <main>
        {corrente === 'dashboard' && <Dashboard key={refresh} />}
        {corrente === 'config' && <ConfigPage me={user} permessi={permessi} onChanged={() => setRefresh((x) => x + 1)} />}
        {corrente === 'insight' && <InsightPage permessi={permessi} />}
        {corrente === 'statistiche' && <StatisticsPage />}
        {corrente === 'ricerca' && <RicercaPage />}
        {corrente === 'report' && <ReportTab />}
        {corrente === 'help' && <HelpTab />}
        {corrente === 'manuale' && <ManualePage />}
        {corrente === 'about' && <AboutPage />}
      </main>
    </div>
    </PermessiContext.Provider>
  );
}

/* Primo accesso o password reimpostata: l'app resta bloccata finché l'utente non sceglie una nuova password. */
function CambioPasswordObbligatorio({ onDone, onLogout }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function save(e) {
    e.preventDefault();
    setError('');
    if (next !== confirm) { setError('La nuova password e la conferma non coincidono'); return; }
    if (next === current) { setError('La nuova password deve essere diversa da quella attuale'); return; }
    setBusy(true);
    try {
      await api('/password', { method: 'POST', body: { old: current, new: next } });
      onDone();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function esci() {
    try { await api('/logout', { method: 'POST' }); } catch { /* sessione già chiusa */ }
    onLogout();
  }

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={save}>
        <div className="login-brand"><Brand /></div>
        <p className="muted small">Per motivi di sicurezza devi scegliere una nuova password prima di usare il monitor.</p>
        <label className="field"><span>Password attuale</span>
          <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} /></label>
        <label className="field"><span>Nuova password (almeno 6 caratteri)</span>
          <input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} /></label>
        <label className="field"><span>Conferma nuova password</span>
          <input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} /></label>
        {error && <div className="login-error" role="alert">{error}</div>}
        <button type="submit" className="login-submit" disabled={busy || !current || !next || !confirm}>
          {busy ? 'Salvataggio…' : 'Imposta la nuova password'}
        </button>
        <button type="button" className="secondary" onClick={esci}>Esci</button>
      </form>
    </div>
  );
}

export default function App() {
  // loading: verifica della sessione in corso; in: utente autenticato; out: serve l'accesso
  const vuoto = { state: 'out', user: null, permessi: [], cambioPassword: false };
  const [auth, setAuth] = useState({ state: 'loading', user: null, permessi: [], cambioPassword: false });

  useEffect(() => {
    const expired = () => setAuth(vuoto);
    window.addEventListener('dbg-auth-expired', expired);
    api('/session')
      .then((d) => setAuth(d.user ? { state: 'in', user: d.user, permessi: d.permessi || [], cambioPassword: !!d.cambioPassword } : vuoto))
      .catch(() => setAuth(vuoto));
    return () => window.removeEventListener('dbg-auth-expired', expired);
  }, []);

  if (auth.state === 'loading') return <div className="app"><p className="muted">Caricamento…</p></div>;
  if (auth.state === 'out') {
    return <LoginPage onLogin={(d) => setAuth({ state: 'in', user: d.user, permessi: d.permessi || [], cambioPassword: !!d.cambioPassword })} />;
  }
  if (auth.cambioPassword) {
    return <CambioPasswordObbligatorio
      onDone={() => setAuth({ ...auth, cambioPassword: false })}
      onLogout={() => setAuth(vuoto)} />;
  }
  return <Console user={auth.user} permessi={auth.permessi} onLogout={() => setAuth(vuoto)} />;
}
