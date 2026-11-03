// Catalogo dei controlli eseguiti dal monitor.
// Le categorie e gli eventi corrispondono a Monitor.java e Docker.java.

export const POLL_LABEL = 'ogni 60 secondi (POLL_SECONDS)';

export const CHECKS = [
  {
    categoria: 'Connessione',
    controlli: [
      {
        nome: 'Test di connessione JDBC',
        rilevazione: 'Apertura della connessione JDBC con le credenziali in anagrafica; SELECT @@version',
        frequenza: 'ogni rilevamento e a richiesta (pulsante Testa)',
        evento: 'CONNECTION_LOST (CRITICAL) quando il server non risponde; CONNECTION_RESTORED (INFO) al ritorno',
      },
    ],
  },
  {
    categoria: 'Edizione e versione',
    controlli: [
      {
        nome: 'Edizione Community o Enterprise',
        rilevazione: '@@version_comment: "Enterprise" o "Community"',
        frequenza: POLL_LABEL,
        evento: 'EDITION_CHANGE (WARNING) se cambia',
      },
      {
        nome: 'Versione e livello di patch',
        rilevazione: '@@version (es. 8.4.13)',
        frequenza: POLL_LABEL,
        evento: 'VERSION_CHANGE (WARNING) se cambia',
      },
      {
        nome: 'Identità del server',
        rilevazione: 'hostname, server_uuid, porta, read_only, super_read_only',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
    ],
  },
  {
    categoria: 'Topologia',
    controlli: [
      {
        nome: 'Tipo di installazione',
        rilevazione: 'Membri in performance_schema.replication_group_members = InnoDB Cluster; SHOW REPLICA STATUS con sorgente = replica; altrimenti nodo singolo',
        frequenza: POLL_LABEL,
        evento: 'TOPOLOGY_CHANGE (WARNING) se cambia',
      },
      {
        nome: 'Nome del cluster',
        rilevazione: 'mysql_innodb_cluster_metadata.clusters',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
    ],
  },
  {
    categoria: 'Cluster InnoDB',
    controlli: [
      {
        nome: 'Stato del cluster',
        rilevazione: 'Membri ONLINE sul totale dei membri configurati: ONLINE, DEGRADED, SENZA QUORUM o OFFLINE',
        frequenza: POLL_LABEL,
        evento: 'CLUSTER_STATUS: CRITICAL se non ONLINE, INFO al ritorno a ONLINE',
      },
      {
        nome: 'Stato di ogni membro',
        rilevazione: 'MEMBER_STATE e MEMBER_ROLE da replication_group_members',
        frequenza: POLL_LABEL,
        evento: 'MEMBER_STATE: WARNING se un membro non è ONLINE, INFO al ritorno',
      },
      {
        nome: 'Nodo più e meno aggiornato',
        rilevazione: 'Transazioni in @@global.gtid_executed su ogni nodo; i nodi irraggiungibili sono segnalati',
        frequenza: 'solo se il cluster non è ONLINE, nel report',
        evento: 'nessuno (informativo)',
      },
    ],
  },
  {
    categoria: 'Replica e relay log',
    controlli: [
      {
        nome: 'Thread IO e SQL di replica',
        rilevazione: 'SHOW REPLICA STATUS: Replica_IO_Running e Replica_SQL_Running per canale',
        frequenza: POLL_LABEL,
        evento: 'REPLICA_THREAD: CRITICAL se un thread non è "Yes", INFO al ritorno',
      },
      {
        nome: 'Ultimo errore di replica',
        rilevazione: 'Last_Error per canale',
        frequenza: POLL_LABEL,
        evento: 'REPLICA_ERROR (WARNING) quando compare un nuovo errore',
      },
      {
        nome: 'Dimensione dei relay log e ritardo',
        rilevazione: 'Relay_Log_Space e Seconds_Behind_Source (canali di replica e GR applier)',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
    ],
  },
  {
    categoria: 'Query',
    controlli: [
      {
        nome: 'Query attive per consumo',
        rilevazione: 'performance_schema.events_statements_current: tempo di esecuzione, poi righe esaminate; esclusi i thread interni e la sessione di monitoraggio',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo); piano di esecuzione a richiesta con EXPLAIN',
      },
      {
        nome: 'Query più lente',
        rilevazione: 'performance_schema.events_statements_summary_by_digest, ordinate per tempo medio',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
    ],
  },
  {
    categoria: 'Indici',
    controlli: [
      {
        nome: 'Elenco degli indici',
        rilevazione: 'information_schema.STATISTICS, esclusi mysql, sys, information_schema, performance_schema',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
      {
        nome: 'Tabelle senza primary key',
        rilevazione: 'information_schema.TABLES senza indice PRIMARY',
        frequenza: POLL_LABEL,
        evento: 'PK_MISSING (WARNING) quando il numero aumenta',
      },
      {
        nome: 'Indici inutilizzati',
        rilevazione: 'sys.schema_unused_indexes (se lo schema sys è presente)',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
    ],
  },
  {
    categoria: 'Utenti e privilegi',
    controlli: [
      {
        nome: 'Utenti configurati',
        rilevazione: 'mysql.user: plugin, account bloccato, password scaduta, privilegi globali',
        frequenza: POLL_LABEL,
        evento: 'USERS_CHANGED (INFO) quando un utente viene aggiunto o rimosso',
      },
      {
        nome: 'Database accessibili per utente',
        rilevazione: 'mysql.db',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
    ],
  },
  {
    categoria: 'Risorse',
    controlli: [
      {
        nome: 'CPU del container',
        rilevazione: 'Statistiche Docker del container (proxy in sola lettura); 100% = un core occupato',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
      {
        nome: 'Memoria del container',
        rilevazione: 'Statistiche Docker: uso meno cache, limite e percentuale',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
      {
        nome: 'Memoria allocata da mysqld e buffer pool',
        rilevazione: 'sys.memory_global_total e @@innodb_buffer_pool_size',
        frequenza: POLL_LABEL,
        evento: 'nessuno (informativo)',
      },
    ],
  },
  {
    categoria: 'Monitoraggio e storico',
    controlli: [
      {
        nome: 'Snapshot dei rilevamenti',
        rilevazione: 'Ogni rilevamento salvato in MariaDB (tabella snapshots)',
        frequenza: POLL_LABEL,
        evento: 'conservati 7 giorni',
      },
      {
        nome: 'Storico eventi',
        rilevazione: 'Eventi generati dai confronti tra rilevamenti (tabella events)',
        frequenza: 'a ogni variazione',
        evento: 'conservati 90 giorni',
      },
      {
        nome: 'Storico configurazioni',
        rilevazione: 'Creazione, modifica ed eliminazione dei server in anagrafica (tabella config_log, senza password)',
        frequenza: 'a ogni modifica',
        evento: 'nessuno (registro)',
      },
    ],
  },
];
