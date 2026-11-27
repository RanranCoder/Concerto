-- Run once as the administrator of a dedicated, empty Concerto database.
\set ON_ERROR_STOP on
\if :{?migrator}
\else
\set migrator pams_migrator
\endif
\if :{?runtime}
\else
\set runtime pams_runtime
\endif
\if :{?candidate}
\else
\set candidate pams_candidate
\endif

BEGIN;

CREATE ROLE :"migrator" LOGIN;
CREATE ROLE :"runtime" LOGIN;
CREATE ROLE :"candidate" LOGIN;

REVOKE CONNECT, TEMPORARY ON DATABASE :"DBNAME" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"DBNAME" TO :"migrator", :"runtime", :"candidate";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA pams AUTHORIZATION :"migrator";
CREATE SCHEMA pams_flyway AUTHORIZATION :"migrator";
GRANT USAGE ON SCHEMA pams, pams_flyway TO :"runtime", :"candidate";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA pams
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA pams
    GRANT USAGE ON SEQUENCES TO :"runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA pams
    GRANT SELECT ON TABLES TO :"candidate";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA pams_flyway
    GRANT SELECT ON TABLES TO :"runtime", :"candidate";

ALTER ROLE :"migrator" IN DATABASE :"DBNAME" SET timezone TO 'Asia/Shanghai';
ALTER ROLE :"runtime" IN DATABASE :"DBNAME" SET timezone TO 'Asia/Shanghai';
ALTER ROLE :"candidate" IN DATABASE :"DBNAME" SET timezone TO 'Asia/Shanghai';
ALTER ROLE :"runtime" IN DATABASE :"DBNAME" SET search_path TO pams;
ALTER ROLE :"candidate" IN DATABASE :"DBNAME" SET search_path TO pams;
ALTER ROLE :"candidate" IN DATABASE :"DBNAME" SET default_transaction_read_only TO on;
COMMIT;
