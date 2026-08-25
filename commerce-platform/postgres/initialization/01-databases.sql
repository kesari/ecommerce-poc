CREATE ROLE account_service LOGIN PASSWORD 'account_service';
CREATE DATABASE account_service OWNER account_service;

CREATE ROLE catalog_service LOGIN PASSWORD 'catalog_service';
CREATE DATABASE catalog_service OWNER catalog_service;

CREATE ROLE basket_service LOGIN PASSWORD 'basket_service';
CREATE DATABASE basket_service OWNER basket_service;

CREATE ROLE inventory_service LOGIN PASSWORD 'inventory_service';
CREATE DATABASE inventory_service OWNER inventory_service;

CREATE ROLE order_service LOGIN PASSWORD 'order_service';
CREATE DATABASE order_service OWNER order_service;

CREATE ROLE payment_service LOGIN PASSWORD 'payment_service';
CREATE DATABASE payment_service OWNER payment_service;

CREATE ROLE shipment_service LOGIN PASSWORD 'shipment_service';
CREATE DATABASE shipment_service OWNER shipment_service;
