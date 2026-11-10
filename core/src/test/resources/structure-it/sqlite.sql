-- The SQLite counterpart of postgres.sql, for IntrospectionGoldenTest (and the Rust driver's introspection test).
CREATE TABLE customers (id INTEGER PRIMARY KEY AUTOINCREMENT, email varchar(120) NOT NULL UNIQUE, name text);
CREATE TABLE orders (
  id integer NOT NULL,
  customer_id integer NOT NULL REFERENCES customers(id) ON DELETE CASCADE ON UPDATE SET NULL,
  total numeric(10,2) NOT NULL DEFAULT 0,
  qty int NOT NULL DEFAULT 1,
  total_with_tax real GENERATED ALWAYS AS (total * 1.1) STORED,
  status text NOT NULL DEFAULT 'new',
  note TEXT DEFAULT (lower('X')),
  created timestamp DEFAULT CURRENT_TIMESTAMP,
  payload BLOB,
  flag boolean,
  ratio double precision,
  code char(3),
  CONSTRAINT orders_pk PRIMARY KEY (id),
  CONSTRAINT positive_total CHECK (total >= 0),
  CHECK (qty > 0)
);
CREATE INDEX orders_status ON orders (status) WHERE status <> 'done';
CREATE INDEX orders_lower ON orders (lower(status));
CREATE UNIQUE INDEX orders_customer_status ON orders (customer_id, status);
CREATE TRIGGER orders_touch BEFORE INSERT ON orders FOR EACH ROW BEGIN SELECT 1; END;
CREATE TRIGGER orders_audit AFTER DELETE ON orders BEGIN SELECT 1; END;
CREATE VIEW big_orders AS SELECT id, total FROM orders WHERE total > 100;
CREATE TABLE products (sku text PRIMARY KEY, name text) WITHOUT ROWID;
CREATE TABLE line_items (
  order_id integer,
  line integer,
  sku text,
  PRIMARY KEY (order_id, line),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT line_product FOREIGN KEY (sku) REFERENCES products(sku) ON DELETE SET DEFAULT
);
CREATE TABLE "Mixed Case" ("Id" int PRIMARY KEY, "a""b" text, [odd col] varchar);
CREATE TABLE user_x (id integer);
CREATE TABLE userAx (id integer);
CREATE TABLE tree (id integer PRIMARY KEY, parent integer REFERENCES tree(id));
INSERT INTO customers (email, name) VALUES ('a@x.io', 'Ann'), ('b@x.io', NULL);
INSERT INTO orders (id, customer_id, total, qty, status, payload, flag, ratio, code, created)
  VALUES (1, 1, 150.5, 2, 'new', x'00FF10', 1, 0.25, 'abc', '2024-01-02 03:04:05'), (2, 2, 3000000000, 1, 'done', NULL, 0, 1e300, NULL, NULL);
