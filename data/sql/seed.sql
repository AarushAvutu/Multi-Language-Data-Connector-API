-- Demo database for the "sales-db" JDBC connector (H2, in-memory).
-- Swap the JDBC url in config/connectors.json to point at PostgreSQL/MySQL instead.

CREATE TABLE regions (
  region_id   INT PRIMARY KEY,
  region_name VARCHAR(50) NOT NULL,
  manager     VARCHAR(100)
);

CREATE TABLE orders (
  order_id     BIGINT PRIMARY KEY,
  customer_id  INT NOT NULL,
  region_id    INT NOT NULL REFERENCES regions(region_id),
  order_date   DATE NOT NULL,
  amount       DECIMAL(10, 2) NOT NULL,
  is_shipped   BOOLEAN NOT NULL,
  coupon_code  VARCHAR(20)
);

INSERT INTO regions VALUES
  (1, 'North America', 'Grace Hopper'),
  (2, 'Europe',        'Alan Turing'),
  (3, 'Asia Pacific',  NULL);

INSERT INTO orders VALUES
  (5001, 1, 1, DATE '2026-01-15', 120.50, TRUE,  'WELCOME10'),
  (5002, 2, 1, DATE '2026-01-17',  89.99, TRUE,  NULL),
  (5003, 5, 2, DATE '2026-02-02', 640.00, FALSE, NULL),
  (5004, 4, 1, DATE '2026-02-11',  15.75, TRUE,  'SPRING5'),
  (5005, 10, 3, DATE '2026-03-03', 299.00, TRUE, NULL),
  (5006, 7, 2, DATE '2026-03-21', 1250.00, FALSE, 'VIP20'),
  (5007, 8, 1, DATE '2026-04-08',  42.00, TRUE,  NULL),
  (5008, 1, 1, DATE '2026-04-30', 510.25, TRUE,  NULL);
