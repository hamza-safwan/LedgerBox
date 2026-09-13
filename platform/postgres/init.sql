CREATE USER identity_app WITH PASSWORD 'identity_dev';
CREATE USER customer_app WITH PASSWORD 'customer_dev';
CREATE USER ledger_app WITH PASSWORD 'ledger_dev';
CREATE USER movement_app WITH PASSWORD 'movement_dev';
CREATE USER rail_app WITH PASSWORD 'rail_dev';

CREATE DATABASE identity_db OWNER identity_app;
CREATE DATABASE customer_db OWNER customer_app;
CREATE DATABASE ledger_db OWNER ledger_app;
CREATE DATABASE movement_db OWNER movement_app;
CREATE DATABASE rail_db OWNER rail_app;

