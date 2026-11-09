OPEN-DBG
Monitor open source per database, senza agent
Autore: Quirino Scaramastra
Progetto a scopo didattico.

Open-DBG è un monitor per MySQL, MariaDB, PostgreSQL, Microsoft SQL Server e MongoDB.
Non installa nulla sui server monitorati: si collega con i driver di connessione, in sola
lettura, e raccoglie quello che il database espone.

Laboratorio sperimentale, versione Alfa da non usare in produzione. Nessun supporto, nessuna garanzia, nessuna
manutenzione programmata. Non usarlo in produzione senza test.


COSA FA
-------

- Dashboard con tutti i server, cluster, repliche e server singoli, con uno stato per ciascuno.
- Cluster InnoDB e Galera: nodo primario, membri e, a cluster fermo, il nodo candidato al ripristino.
- MongoDB: stato del replica set, ritardo, oplog, operazioni lunghe, cache e connessioni.
- Insight giornaliero: punteggio da 0 a 100 per ogni server, con controlli, esiti e raccomandazioni.
  I controlli selezionabili dipendono dalla tecnologia del server.
- Funzioni IA on demand e commento IA opzionale sul report giornaliero, con Anthropic, OpenAI,
  entrambi o un modello locale con Ollama. Le funzioni IA producono solo testo: non eseguono
  nulla sui database.
- Notifiche Telegram degli eventi, con conferma di presa visione.
- Ricerca di un database per nome su tutti i server visibili.
- Piano di esecuzione (EXPLAIN) delle query lente, senza eseguire la query.
- Metriche dell'host (CPU e memoria) facoltative, lette da node_exporter.
- Pulsante "Termina sessione" per le transazioni bloccate, con un'utenza separata che ha solo
  il permesso di terminare sessioni (vedere PERMESSI.txt).
- Configurazione per server: controlli da eseguire, ambiente, sito, partecipazione all'Insight.
  Un server con "Sospendi monitoraggio" non viene connesso affatto.
- Utenti con profili e server assegnati.

Non modifica i database monitorati. Le metriche del sistema operativo si leggono solo se si
indica l'indirizzo di node_exporter nella scheda del server.


INSTALLAZIONE E AVVIO
---------------------

1. Verificare i requisiti in PREREQUISITES.txt.

2. Avviare con lo script del proprio sistema, dalla cartella del progetto.

   Linux e macOS:

       ./avvia.sh

   Windows (PowerShell, con Docker Desktop in esecuzione):

       .\avvia.ps1

   Lo script crea il file .env dal modello .env.example la prima volta, genera
   APP_SECRET_KEY e avvia i container con "docker compose up -d --build".

   APP_SECRET_KEY cifra le password dei server monitorati e le chiavi API. Non è nel database:
   se si perde, le password salvate non si possono più leggere. Conservarne una copia al sicuro,
   e non cambiarla dopo il primo avvio.

3. Aprire http://localhost:8080 e accedere con:

       utente: admin
       password: admin

   Al primo accesso il monitor obbliga a cambiare la password.
   Le password di DB_PASSWORD e DB_ROOT_PASSWORD (in .env) sono quelle del database
   del monitor. Il default "pippo" e "admin" servono solo per il laboratorio locale: vanno
   cambiati prima di qualsiasi uso diverso da una prova.


DOVE SONO I DATI
----------------

Il database del monitor usa il volume Docker db_data. I dati non stanno nella cartella
del progetto né nel container: Docker li conserva a parte, quindi restano anche quando
il container viene ricreato o aggiornato.

    docker compose down          ferma e rimuove i container, i dati restano
    docker compose down -v       rimuove anche il volume: tutti i dati vengono cancellati

Backup del volume (Linux e macOS):

    docker run --rm -v open-dbg_db_data:/dati -v "$PWD":/backup alpine tar czf /backup/open-dbg-db.tgz -C /dati .

Backup del volume (Windows, PowerShell):

    docker run --rm -v open-dbg_db_data:/dati -v "${PWD}:/backup" alpine tar czf /backup/open-dbg-db.tgz -C /dati .

Il nome del volume dipende dalla cartella del progetto: se la cartella si chiama
open-dbg, il volume è open-dbg_db_data. Verificarlo con "docker volume ls".
Se hai già un'installazione nella cartella dbguardian, i dati sono nel volume dbguardian_db_data:
fai il backup prima di rinominare la cartella, altrimenti Docker crea un volume nuovo e vuoto.

Ripristino: con il monitor fermo, si ricrea il volume e si estrae l'archivio dentro di esso
con lo stesso comando, usando "tar xzf /backup/open-dbg-db.tgz -C /dati".


ARRESTO
-------

    docker compose down


AGGIUNGERE UN SERVER
--------------------

Nella scheda "Server da monitorare" (solo amministratore):

- nome, tipo (MySQL o MariaDB, PostgreSQL, SQL Server, MongoDB, router, database del monitor);
- host e porta raggiungibili dal container del monitor (MongoDB usa 27017 di default);
- utente di sola lettura (vedere PERMESSI.txt);
- ambiente, sito e, se presente, il cluster Galera;
- il pulsante di test verifica la connessione prima del salvataggio;
- "Includi nel report Insight" decide se il server entra nel report giornaliero.

Il container del monitor deve poter raggiungere gli host dei server. Se i server sono in
un'altra rete Docker, collegare il monitor a quella rete nel docker-compose.yml, nella sezione
del servizio mon_monitor, con la stessa sintassi della rete "default".


TELEGRAM (FACOLTATIVO)
----------------------

1. Creare un bot con @BotFather e copiarne il token.
2. Aggiungere il bot al gruppo o canale che riceverà le notifiche.
3. Nella scheda "Telegram" inserire token e chat ID (per un gruppo è un numero negativo).
4. Abilitare e inviare il messaggio di prova.

Ogni avviso ha il pulsante "Presa visione". Finché non viene confermato, lo stesso
evento non viene inviato di nuovo. Il monitor riceve i clic interrogando Telegram,
senza aprire porte in ingresso: un solo processo alla volta può usare lo stesso token.


IA (FACOLTATIVO)
----------------

Nella scheda "Insight IA" si sceglie il provider (Anthropic, OpenAI, entrambi, o Ollama
sul computer che ospita Docker), si inseriscono le chiavi e i modelli, e si usa
"Prova connessione". Con Ollama il container raggiunge il servizio dell'host come
host.docker.internal.

Dati inviati al provider: nomi dei server, versioni, punteggi ed esiti dei controlli.
Non vengono inviati indirizzi di rete, utenti, password né stringhe di connessione.
Gli esiti possono contenere nomi di utenti, database o tabelle.


MANUALE NELL'APPLICAZIONE
-------------------------

La scheda "Manuale" dell'applicazione descrive le singole funzioni e la scheda
"About" riporta il nome del progetto, l'autore e la licenza.
