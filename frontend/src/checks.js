// Catalogo dei controlli eseguiti dal monitor, per tecnologia.
// Ogni voce descrive cosa viene letto (rilevazione), con quale cadenza e cosa produce (evento o esito).
// Le cadenze dipendono dai parametri di rilevamento (Parametri): i valori indicati sono i predefiniti.
// Fonti: backend/src/main/java/it/monitor (Main, PgReport, SqlServerReport, Statistiche, Insight, ConfigVars, MongoReport).

export const CADENZE = {
  raggiungibilita: 'ogni 15 s (raggiungibilità): un cambio di stato viene registrato subito',
  leggera: 'ogni 5 min (stato leggero: connessione, replica, cluster, query attive)',
  pesante: 'ogni 15 min (sezioni pesanti: indici, utenti, dimensione dati)',
  insight: 'una volta al giorno, all\'ora del report Insight in Parametri (predefinito 06:00), o su richiesta',
  aItEvento: 'a ogni variazione',
};

export const CHECKS = [
  {
    tecnologia: 'Comuni a tutti i motori',
    categorie: [
      {
        categoria: 'Connessione',
        controlli: [
          {
            nome: 'Raggiungibilità e connessione',
            rilevazione: 'Connessione con le credenziali in anagrafica; eseguita ogni 15 s per la raggiungibilità',
            frequenza: CADENZE.raggiungibilita,
            evento: 'CONNECTION_LOST (CRITICAL) quando il server non risponde; CONNECTION_RESTORED (INFO) al ritorno',
          },
        ],
      },
      {
        categoria: 'Storico e conservazione',
        controlli: [
          {
            nome: 'Snapshot dei rilevamenti',
            rilevazione: 'Ogni rilevamento salvato nel database del monitor (tabella snapshots)',
            frequenza: CADENZE.leggera,
            evento: 'conservati per il numero di giorni in Parametri (predefinito 7)',
          },
          {
            nome: 'Storico eventi',
            rilevazione: 'Eventi generati dai confronti tra rilevamenti (tabella events)',
            frequenza: CADENZE.aItEvento,
            evento: 'conservati per il numero di giorni in Parametri (predefinito 90)',
          },
          {
            nome: 'Storico configurazioni dei server',
            rilevazione: 'Creazione, modifica ed eliminazione dei server in anagrafica (tabella config_log, senza password)',
            frequenza: 'a ogni modifica',
            evento: 'nessuno (registro)',
          },
        ],
      },
    ],
  },
  {
    tecnologia: 'MySQL 8.x e MariaDB',
    nota: 'Stesso kind "server". I controlli di configurazione leggono le variabili disponibili: alcune esistono solo in un motore (indicato per ciascuna).',
    categorie: [
      {
        categoria: 'Identità e versione',
        controlli: [
          {
            nome: 'Edizione',
            rilevazione: '@@version_comment: MySQL Enterprise o Community, Percona Server, MariaDB',
            frequenza: CADENZE.leggera,
            evento: 'EDITION_CHANGE (WARNING) se cambia',
          },
          {
            nome: 'Versione e patch',
            rilevazione: '@@version (es. 8.4.13, 11.4.5-MariaDB)',
            frequenza: CADENZE.leggera,
            evento: 'VERSION_CHANGE (WARNING) se cambia',
          },
          {
            nome: 'Fine supporto della versione',
            rilevazione: 'Confronto con il ciclo di vita: MySQL 8.0 fuori supporto (EOL aprile 2026), MySQL 8.4 LTS fino al 2032; MariaDB 10.6 fuori supporto dal 6 luglio 2026, 10.11 fino a febbraio 2028, 11.4 e 11.8 LTS',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report Insight)',
          },
          {
            nome: 'Identità del nodo',
            rilevazione: 'hostname, server_uuid, porta, read_only, super_read_only',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Topologia',
        controlli: [
          {
            nome: 'Tipo di installazione',
            rilevazione: 'InnoDB Cluster: membri in performance_schema.replication_group_members. Replica: SHOW REPLICA STATUS con sorgente. Galera: wsrep_cluster_size > 0. Altrimenti nodo singolo',
            frequenza: CADENZE.leggera,
            evento: 'TOPOLOGY_CHANGE (WARNING) se cambia',
          },
          {
            nome: 'Nome del cluster (InnoDB Cluster)',
            rilevazione: 'mysql_innodb_cluster_metadata.clusters (solo se lo schema metadati esiste)',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Cluster InnoDB',
        controlli: [
          {
            nome: 'Stato del cluster',
            rilevazione: 'Membri ONLINE sul totale: ONLINE, DEGRADED, SENZA QUORUM o OFFLINE',
            frequenza: CADENZE.leggera,
            evento: 'CLUSTER_STATUS: CRITICAL se non ONLINE; INFO al ritorno a ONLINE',
          },
          {
            nome: 'Stato di ogni membro',
            rilevazione: 'MEMBER_STATE e MEMBER_ROLE da performance_schema.replication_group_members',
            frequenza: CADENZE.leggera,
            evento: 'MEMBER_STATE: WARNING se un membro non è ONLINE; INFO al ritorno',
          },
          {
            nome: 'Nodo più e meno aggiornato',
            rilevazione: '@@global.gtid_executed su ogni nodo; i nodi irraggiungibili sono segnalati',
            frequenza: 'ad ogni rilevamento del cluster',
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Galera (MariaDB Galera Cluster)',
        controlli: [
          {
            nome: 'Stato del nodo Galera',
            rilevazione: 'SHOW GLOBAL STATUS LIKE wsrep_%: wsrep_local_state_comment, wsrep_cluster_status, wsrep_cluster_size. Nome del cluster e del nodo da wsrep_cluster_name e wsrep_node_name',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo); nel report Insight il nodo non Synced è WARNING',
          },
          {
            nome: 'Stato di sincronizzazione (Insight)',
            rilevazione: 'wsrep_local_state_comment diverso da Synced',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report Insight)',
          },
        ],
      },
      {
        categoria: 'Replica e relay log',
        controlli: [
          {
            nome: 'Thread IO e SQL di replica',
            rilevazione: 'SHOW REPLICA STATUS: Replica_IO_Running e Replica_SQL_Running per canale',
            frequenza: CADENZE.leggera,
            evento: 'REPLICA_THREAD: CRITICAL se un thread non è "Yes"; INFO al ritorno',
          },
          {
            nome: 'Ultimo errore di replica',
            rilevazione: 'Last_Error per canale',
            frequenza: CADENZE.leggera,
            evento: 'REPLICA_ERROR (WARNING) quando compare un nuovo errore',
          },
          {
            nome: 'Ritardo di replica e relay log',
            rilevazione: 'Seconds_Behind_Source e Relay_Log_Space per canale; con Group Replication il ritardo dell\'applier',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo); nel report Insight oltre la soglia in Parametri (predefinito 60 s) è WARNING',
          },
        ],
      },
      {
        categoria: 'Query',
        controlli: [
          {
            nome: 'Query attive per consumo',
            rilevazione: 'performance_schema.events_statements_current unito a threads: tempo di esecuzione, poi righe esaminate; esclusi i thread interni e la sessione di monitoraggio',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo); nel report Insight, oltre la soglia in Parametri, è WARNING',
          },
          {
            nome: 'Query più lente per digest',
            rilevazione: 'performance_schema.events_statements_summary_by_digest, per tempo medio. SENZA_INDICE da SUM_NO_INDEX_USED',
            frequenza: CADENZE.leggera,
            evento: 'nel report Insight: query lente senza indice utilizzabile è WARNING',
          },
          {
            nome: 'Piano di esecuzione',
            rilevazione: 'EXPLAIN della query selezionata, a richiesta (solo utenti con il permesso diagnostica)',
            frequenza: 'a richiesta',
            evento: 'nessuno',
          },
        ],
      },
      {
        categoria: 'Indici e schema',
        controlli: [
          {
            nome: 'Tabelle senza primary key',
            rilevazione: 'information_schema.TABLES senza indice PRIMARY (esclusi mysql, sys, information_schema, performance_schema)',
            frequenza: CADENZE.pesante,
            evento: 'PK_MISSING (WARNING) quando il numero aumenta',
          },
          {
            nome: 'Indici inutilizzati',
            rilevazione: 'sys.schema_unused_indexes (se lo schema sys è presente)',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Elenco degli indici',
            rilevazione: 'information_schema.STATISTICS',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Colonne vettoriali',
            rilevazione: 'information_schema.COLUMNS con DATA_TYPE = vector (presente solo nelle versioni che supportano il tipo)',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Utenti e privilegi',
        controlli: [
          {
            nome: 'Account e privilegi globali',
            rilevazione: 'mysql.user: plugin, account_locked, password_expired (solo MySQL) e privilegi globali SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, GRANT, SUPER',
            frequenza: CADENZE.pesante,
            evento: 'USERS_CHANGED (INFO) quando un account viene aggiunto o rimosso',
          },
          {
            nome: 'Database accessibili per utente',
            rilevazione: 'mysql.db',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Anomalie degli account (Insight)',
            rilevazione: 'Utenti anonimi, root@% accessibile da qualunque host, password scadute, account bloccati',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report Insight)',
          },
        ],
      },
      {
        categoria: 'Risorse',
        controlli: [
          {
            nome: 'Memoria allocata da mysqld',
            rilevazione: 'sys.memory_global_total e @@innodb_buffer_pool_size',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'CPU e memoria dell\'host',
            rilevazione: 'node_exporter (/metrics) solo in modalità host: CPU in percentuale tra due campioni, memoria usata e totale',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Configurazione (report Insight)',
        controlli: [
          {
            nome: 'Connessioni: picco rispetto a max_connections',
            rilevazione: 'Max_used_connections (SHOW GLOBAL STATUS) e @@max_connections. Soglia in Parametri (predefinito 90%)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Buffer pool InnoDB: hit ratio',
            rilevazione: 'Innodb_buffer_pool_read_requests e Innodb_buffer_pool_reads; calcolato solo oltre il minimo di accessi in Parametri (predefinito 10.000). Soglia hit in Parametri (predefinito 95%)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Tabelle temporanee su disco',
            rilevazione: 'Created_tmp_disk_tables su Created_tmp_tables. Soglia in Parametri (predefinito 25%) con almeno il minimo di tabelle temporanee in Parametri (predefinito 100)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Binary log attivo e formato',
            rilevazione: '@@log_bin; @@binlog_format (MySQL predefinito ROW, MariaDB predefinito MIXED: si segnala se non è ROW)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Scadenza del binary log',
            rilevazione: '@@binlog_expire_logs_seconds (MySQL) oppure @@expire_logs_days (MariaDB): zero significa nessuna scadenza',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Durabilità InnoDB e binlog',
            rilevazione: '@@innodb_flush_log_at_trx_commit (deve essere 1) e @@sync_binlog (deve essere 1 con il binlog attivo)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Slow query log e sql_mode',
            rilevazione: '@@slow_query_log; @@sql_mode deve contenere STRICT_TRANS_TABLES',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'History list di InnoDB',
            rilevazione: 'SHOW ENGINE INNODB STATUS: History list length. Oltre la soglia in Parametri (predefinito 100.000 transazioni) è WARNING: letture o transazioni lunghe bloccano il purge',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Galera: flow control e autoincrement',
            rilevazione: 'wsrep_flow_control_paused oltre il 10% del tempo è WARNING; innodb_autoinc_lock_mode deve essere 2 con Galera',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Storage e cifratura delle connessioni',
            rilevazione: '@@innodb_file_per_table (warning se spento); @@require_secure_transport (informativo se spento)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Sicurezza di base',
            rilevazione: '@@local_infile (deve essere OFF); @@default_authentication_plugin (MySQL 8.0: mysql_native_password è segnalato)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Variabili di configurazione globali',
            rilevazione: 'SHOW GLOBAL VARIABLES confrontato con l\'ultima lettura; i valori sensibili non sono salvati',
            frequenza: CADENZE.pesante,
            evento: 'CONFIG_VARIABLE: WARNING per le variabili critiche, INFO per le altre; più di 50 modifiche insieme diventano un solo evento',
          },
        ],
      },
    ],
  },
  {
    tecnologia: 'PostgreSQL',
    categorie: [
      {
        categoria: 'Identità e versione',
        controlli: [
          {
            nome: 'Versione e fine supporto',
            rilevazione: 'Versione da SELECT version(). Insight: PostgreSQL 14 fine supporto il 12 novembre 2026; versioni precedenti fuori supporto; 17 e successive senza segnalazioni',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report Insight)',
          },
        ],
      },
      {
        categoria: 'Connessioni e query',
        controlli: [
          {
            nome: 'Connessioni client',
            rilevazione: 'count(*) da pg_stat_activity con backend_type = client backend, confrontato con max_connections',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo); nel report Insight oltre la soglia in Parametri (predefinito 90%) è WARNING',
          },
          {
            nome: 'Query attive',
            rilevazione: 'pg_stat_activity con stato diverso da idle, escluso il monitor',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Query più lente',
            rilevazione: 'pg_stat_statements, se l\'estensione è installata; altrimenti il controllo è vuoto',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Transazioni inattive',
            rilevazione: 'pg_stat_activity con stato idle in transaction oltre la soglia in Parametri (predefinito 5 minuti). Bloccano il vacuum',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo); nel report Insight è WARNING',
          },
        ],
      },
      {
        categoria: 'Configurazione (report Insight)',
        controlli: [
          {
            nome: 'WAL: wal_level',
            rilevazione: 'pg_settings: wal_level deve essere replica o logical per la replica in streaming',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Archiviazione WAL',
            rilevazione: 'pg_settings: archive_mode (on o always). Senza di essa non c\'è ripristino point-in-time',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Cifratura delle password',
            rilevazione: 'pg_settings: password_encryption deve essere scram-sha-256',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'TLS (ssl)',
            rilevazione: 'pg_settings: ssl; se off è segnalato come connessioni in chiaro',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Checksum dei dati',
            rilevazione: 'pg_settings: data_checksums (solo informativo se off)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Autovacuum',
            rilevazione: 'pg_settings: autovacuum; se off è segnalato come BAD',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Log delle query lente',
            rilevazione: 'pg_settings: log_min_duration_statement (-1 = disattivato, informativo)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Checkpoint richiesti e programmati',
            rilevazione: 'pg_stat_checkpointer (PostgreSQL 17) o pg_stat_bgwriter (versioni precedenti): num_requested rispetto a num_timed. Contatori cumulativi dall\'ultimo azzeramento',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Hit ratio della cache',
            rilevazione: 'pg_stat_database: blks_hit e blks_read. Calcolato oltre il minimo di accessi in Parametri (predefinito 10.000); soglia in Parametri (predefinito 95%)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
        ],
      },
      {
        categoria: 'Replica e slot',
        controlli: [
          {
            nome: 'Slot di replica inattivi',
            rilevazione: 'pg_replication_slots con active = false: trattengono i WAL e riempiono il disco',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report Insight: WARNING)',
          },
        ],
      },
      {
        categoria: 'Vacuum e indici',
        controlli: [
          {
            nome: 'Wraparound dei XID (controllo "vacuum")',
            rilevazione: 'pg_database: età massima di datfrozenxid. Oltre la soglia in Parametri (predefinito 1.000 milioni) è WARNING; oltre 1.500 milioni è BAD',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Tuple morte per tabella',
            rilevazione: 'pg_stat_user_tables: tabelle con almeno il numero di righe in Parametri (predefinito 1.000) e più della quota di tuple morte in Parametri (predefinito 20%)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report Insight: WARNING)',
          },
          {
            nome: 'Indici inutilizzati e tabelle senza chiave primaria',
            rilevazione: 'pg_stat_user_indexes con idx_scan = 0, esclusi gli indici UNIQUE e primary; tabelle senza vincolo di tipo p in pg_constraint',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Elenco degli indici',
            rilevazione: 'pg_indexes (primi 300 indici)',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Utenti e database',
        controlli: [
          {
            nome: 'Ruoli e scadenza',
            rilevazione: 'pg_roles: rolcanlogin (login o ruolo), rolvaliduntil (scadenza). La data di creazione non è tracciata da PostgreSQL',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Database: dimensione',
            rilevazione: 'pg_database_size(oid) per i database con datallowconn',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Risorse',
        controlli: [
          {
            nome: 'CPU e memoria dell\'host',
            rilevazione: 'node_exporter (/metrics) solo in modalità host',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
        ],
      },
    ],
  },
  {
    tecnologia: 'SQL Server',
    categorie: [
      {
        categoria: 'Identità, versione e topologia',
        controlli: [
          {
            nome: 'Edizione, versione e nodo',
            rilevazione: 'SERVERPROPERTY: Edition, ProductVersion, MachineName, IsHadrEnabled, IsClustered',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Fine supporto della versione',
            rilevazione: 'Major di ProductVersion: 2016 (13) supporto esteso terminato a luglio 2026; 2017 (14) fino a ottobre 2027; 2019 (15) fino a gennaio 2030; 2022 (16) fino a gennaio 2033',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report Insight)',
          },
          {
            nome: 'Topologia',
            rilevazione: 'Standalone, failover cluster (FCI) o Always On: sys.availability_groups e sys.availability_replicas. Standalone è segnalato come assenza di failover',
            frequenza: CADENZE.leggera,
            evento: 'TOPOLOGY_CHANGE (WARNING) se cambia',
          },
        ],
      },
      {
        categoria: 'Connessioni e query',
        controlli: [
          {
            nome: 'Connessioni utente attive',
            rilevazione: 'sys.dm_exec_sessions con is_user_process = 1',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Richieste in esecuzione',
            rilevazione: 'sys.dm_exec_requests unito a sys.dm_exec_sessions e sys.dm_exec_sql_text, ordinate per durata. Oltre la soglia in Parametri è segnalata nel report',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Query più costose',
            rilevazione: 'sys.dm_exec_query_stats con sys.dm_exec_sql_text e sys.dm_exec_plan_attributes (database dal piano)',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Risorse',
        controlli: [
          {
            nome: 'Memoria di sistema e CPU',
            rilevazione: 'sys.dm_os_sys_memory (memoria fisica e disponibile), sys.dm_os_sys_info (cpu_count). La percentuale di CPU non è disponibile dalle DMV',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo); nel report Insight oltre la soglia in Parametri (predefinito 90%) è WARNING',
          },
          {
            nome: 'Memoria del processo SQL Server',
            rilevazione: 'sys.dm_os_process_memory: memoria fisica usata dal processo sqlservr',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Configurazione (report Insight)',
        controlli: [
          {
            nome: 'Memoria massima (max server memory)',
            rilevazione: 'sys.configurations: value_in_use. Segnalata se è al valore predefinito (illimitata)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Parallelismo',
            rilevazione: 'sys.configurations: cost threshold for parallelism (se 5, predefinito, è informativo); max degree of parallelism (se 0, informativo)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'xp_cmdshell',
            rilevazione: 'sys.configurations: xp_cmdshell (se 1 è WARNING)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'File dati di tempdb',
            rilevazione: 'tempdb.sys.database_files (tipo ROWS) confrontato con i CPU: attesi uno per CPU, massimo 8',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Page life expectancy',
            rilevazione: 'sys.dm_os_performance_counters, Buffer Manager: Page life expectancy. Sotto la soglia in Parametri (predefinito 300 s) è WARNING (controllo "risorse")',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Auto close e auto shrink',
            rilevazione: 'sys.databases: is_auto_close_on e is_auto_shrink_on (database utente, ONLINE)',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Verifica delle pagine',
            rilevazione: 'sys.databases: page_verify_option_desc deve essere CHECKSUM',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
        ],
      },
      {
        categoria: 'Backup',
        controlli: [
          {
            nome: 'Ultimo backup completo per database',
            rilevazione: 'msdb.dbo.backupset, tipo D, per database utente ONLINE. Assente = BAD; più vecchio della soglia in Parametri (predefinito 1 giorno) = WARNING',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Ultimo backup del log',
            rilevazione: 'msdb.dbo.backupset, tipo L, per i database in modello FULL o BULK_LOGGED. Più vecchio della soglia in Parametri (predefinito 24 ore) = WARNING',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
        ],
      },
      {
        categoria: 'Utenti e database',
        controlli: [
          {
            nome: 'Login e scadenza',
            rilevazione: 'sys.server_principals (tipi S, U, G): data di creazione, disabilitato e giorni alla scadenza da LOGINPROPERTY',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Database: dimensione e stato',
            rilevazione: 'sys.databases con la dimensione dai file in sys.master_files',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Indici',
        controlli: [
          {
            nome: 'Frammentazione e utilizzo degli indici',
            rilevazione: 'sys.dm_db_index_physical_stats (modalità LIMITED) e sys.dm_db_index_usage_stats per letture e scritture: pagina separata "Statistiche", non parte del report Insight',
            frequenza: 'a richiesta dalla pagina Statistiche',
            evento: 'nessuno (informativo)',
          },
        ],
      },
    ],
  },
  {
    tecnologia: 'MongoDB',
    nota: 'Server con tipo "MongoDB". I replica set si riconoscono dal nome del set e i membri vengono raggruppati in un cluster con la topologia. I controlli selezionabili sono in scheda server.',
    categorie: [
      {
        categoria: 'Identità e versione',
        controlli: [
          {
            nome: 'Identità del server',
            rilevazione: 'hello e buildInfo: versione, edizione (Community o Enterprise dai moduli)',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Fine supporto della versione',
            rilevazione: 'Ramo major.minor. Insight: 6.0 fuori supporto dal 31 luglio 2025; 7.0 fino al 31 agosto 2027; 8.0 e 8.3 fino al 31 ottobre 2029; 8.2 fuori supporto dal 31 luglio 2026',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report Insight)',
          },
        ],
      },
      {
        categoria: 'Replica set e topologia',
        controlli: [
          {
            nome: 'Riconoscimento del replica set',
            rilevazione: 'replSetGetStatus: nome del set. Un nodo standalone non ha replica set e non viene raggruppato',
            frequenza: CADENZE.leggera,
            evento: 'TOPOLOGY_CHANGE (WARNING) se cambia',
          },
          {
            nome: 'Membri del replica set',
            rilevazione: 'replSetGetStatus: stato, ruolo, salute, priorità, voti, nascosto, arbitro; ritardo dal primario calcolato sull\'optime',
            frequenza: CADENZE.leggera,
            evento: 'MEMBER_STATE (WARNING) se un membro non è sano; INFO al ritorno',
          },
          {
            nome: 'Stato del replica set',
            rilevazione: 'ONLINE se tutti i membri sono sani e c\'è un primario; DEGRADED se un membro non è sano; SENZA PRIMARY se manca il primario',
            frequenza: CADENZE.leggera,
            evento: 'CLUSTER_STATUS: CRITICAL se non ONLINE; INFO al ritorno a ONLINE',
          },
          {
            nome: 'Numero di membri votanti',
            rilevazione: 'replSetGetConfig: membri votanti (voti) e arbitri. Un numero pari di votanti senza arbitro è WARNING',
            frequenza: CADENZE.insight,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Ritardo di replica dei secondari (controllo "replica")',
            rilevazione: 'Canali di replica del secondario rilevato verso il primario. Nel report Insight, oltre la soglia in Parametri (predefinito 60 s) è WARNING',
            frequenza: CADENZE.leggera,
            evento: 'REPLICA_THREAD se il secondario non è sano',
          },
        ],
      },
      {
        categoria: 'Oplog',
        controlli: [
          {
            nome: 'Finestra dell\'oplog (controllo "oplog")',
            rilevazione: 'local.oplog.rs: primo e ultimo record, ore di storico per riallineare un secondario. Nel report Insight, sotto il minimo in Parametri (predefinito 24 ore) è WARNING',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo); nel report Insight è WARNING',
          },
        ],
      },
      {
        categoria: 'Connessioni e operazioni',
        controlli: [
          {
            nome: 'Connessioni (controllo "connessioni")',
            rilevazione: 'serverStatus: connections (correnti e disponibili; massimo = correnti + disponibili). Nel report Insight oltre la soglia in Parametri (predefinito 90%) è WARNING',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'Operazioni in esecuzione (controllo "query attive")',
            rilevazione: '$currentOp: operazioni attive oltre la soglia in Parametri (query attive). Nel report Insight sono segnalate come WARNING',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Query lente',
        controlli: [
          {
            nome: 'Query più lente dal profiler (controllo "query_lente")',
            rilevazione: 'system.profile di ogni database utente sul nodo rilevato: voci raggruppate per namespace, operazione e piano (COLLSCAN = senza indice). Il profiler lo attiva l\'amministratore con db.setProfilingLevel; il monitor non lo modifica',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo); nel report Insight le query senza indice sono segnalate',
          },
        ],
      },
      {
        categoria: 'Cache e memoria',
        controlli: [
          {
            nome: 'Cache WiredTiger: byte sporchi (controllo "cache")',
            rilevazione: 'serverStatus.wiredTiger.cache: tracked dirty bytes in the cache rispetto a maximum bytes configured. Nel report Insight oltre la soglia in Parametri (predefinito 20%) è WARNING',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (esito nel report)',
          },
          {
            nome: 'Memoria residente di mongod (controllo "risorse")',
            rilevazione: 'serverStatus.mem.resident e bytes in cache WiredTiger',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
          {
            nome: 'CPU e memoria dell\'host',
            rilevazione: 'node_exporter (/metrics) solo in modalità host',
            frequenza: CADENZE.leggera,
            evento: 'nessuno (informativo)',
          },
        ],
      },
      {
        categoria: 'Database',
        controlli: [
          {
            nome: 'Dimensione dei database (controllo "database")',
            rilevazione: 'listDatabases: sizeOnDisk per database',
            frequenza: CADENZE.pesante,
            evento: 'nessuno (informativo)',
          },
        ],
      },
    ],
  },
];
