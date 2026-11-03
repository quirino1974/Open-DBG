DBGUARDIAN
Monitor open source per database, senza agent
Autore: Quirino Scaramastra

DBGuardian è un monitor per MySQL, MariaDB, PostgreSQL e Microsoft SQL Server.
Non installa nulla sui server monitorati: si collega via JDBC, in sola lettura,
e raccoglie quello che il database espone.

Laboratorio sperimentale, versione Alfa. Nessun supporto, nessuna garanzia,
nessuna manutenzione programmata. Non usarlo in produzione senza test.


COSA FA
-------

- Dashboard con tutti i server, cluster, repliche e server singoli, con uno stato per ciascuno.
- Cluster InnoDB e Galera: nodo primario, membri e, a cluster fermo, il nodo candidato al ripristino.
- Insight giornaliero: punteggio da 0 a 100 per ogni server, con controlli, esiti e raccomandazioni.
- Commento IA opzionale sul report giornaliero, con Anthropic, OpenAI o entrambi.
- Notifiche Telegram degli eventi, con conferma di presa visione.
- Ricerca di un database per nome su tutti i server visibili.
- Piano di esecuzione (EXPLAIN) delle query lente, senza eseguire la query.
- Configurazione per server: controlli da eseguire, ambiente, sito, partecipazione all'Insight.
- Utenti con profili e server assegnati.

Non modifica i database monitorati e non raccoglie metriche del sistema operativo.


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

   APP_SECRET_KEY cifra le password dei server monitorati. Non è nel database: se si
   perde, le password salvate non si possono più leggere. Conservarne una copia al sicuro,
   e non cambiarla dopo il primo avvio.

3. Aprire http://localhost:8080 e accedere con:

       utente: admin
       password: admin

   Al primo accesso il monitor obbliga a cambiare la password.
   Le password di DB_PASSWORD e DB_ROOT_PASSWORD (in .env) sono quelle del database
   del monitor. Il default "pippo" serve solo per il laboratorio locale.


DOVE SONO I DATI
----------------

Il database del monitor usa il volume Docker db_data. I dati non stanno nella cartella
del progetto né nel container: Docker li conserva a parte, quindi restano anche quando
il container viene ricreato o aggiornato.

    docker compose down          ferma e rimuove i container, i dati restano
    docker compose down -v       rimuove anche il volume: tutti i dati vengono cancellati

Backup del volume (Linux e macOS):

    docker run --rm -v mon_monitor_db_data:/dati -v "$PWD":/backup alpine tar czf /backup/dbguardian-db.tgz -C /dati .

Backup del volume (Windows, PowerShell):

    docker run --rm -v mon_monitor_db_data:/dati -v "${PWD}:/backup" alpine tar czf /backup/dbguardian-db.tgz -C /dati .

Il nome del volume dipende dalla cartella del progetto: se la cartella si chiama
dbguardian, il volume è dbguardian_db_data. Verificarlo con "docker volume ls".

Ripristino: con il monitor fermo, si ricrea il volume e si estrae l'archivio dentro di esso
con lo stesso comando, usando "tar xzf /backup/dbguardian-db.tgz -C /dati".


ARRESTO
-------

    docker compose down


AGGIUNGERE UN SERVER
--------------------

Nella scheda "Server da monitorare" (solo amministratore):

- nome, tipo (MySQL o MariaDB, PostgreSQL, SQL Server, router, database del monitor);
- host e porta raggiungibili dal container del monitor;
- utente di sola lettura (vedere PERMESSI.txt);
- ambiente, sito e, se presente, il cluster Galera;
- il pulsante di test verifica la connessione prima del salvataggio;
- "Includi nel report Insight" decide se il server entra nel report giornaliero.

Il container del monitor deve poter raggiungere gli host dei server. Se i server sono
in un'altra rete Docker, collegare il monitor a quella rete nel docker-compose.yml.


TELEGRAM (FACOLTATIVO)
----------------------

1. Creare un bot con @BotFather e copiarne il token.
2. Aggiungere il bot al gruppo o canale che riceverà le notifiche.
3. Nella scheda "Telegram" inserire token e chat ID (per un gruppo è un numero negativo).
4. Abilitare e inviare il messaggio di prova.

Ogni avviso ha il pulsante "Presa visione". Finché non viene confermato, lo stesso
evento non viene inviato di nuovo. Il monitor riceve i clic interrogando Telegram,
senza aprire porte in ingresso: un solo processo alla volta può usare lo stesso token.


COMMENTO IA (FACOLTATIVO)
-------------------------

Nella scheda "Insight IA" si sceglie il provider (Anthropic, OpenAI o entrambi),
si inseriscono le chiavi e i modelli, e si usa "Prova connessione".

Dati inviati al provider: nomi dei server, versioni, punteggi ed esiti dei controlli.
Non vengono inviati indirizzi di rete, utenti, password né stringhe di connessione.
Gli esiti possono contenere nomi di utenti, database o tabelle.


MANUALE NELL'APPLICAZIONE
-------------------------

La scheda "Manuale" dell'applicazione descrive le singole funzioni e la scheda
"About" riporta il nome del progetto, l'autore e la licenza.
