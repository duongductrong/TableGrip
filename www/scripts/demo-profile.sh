#!/usr/bin/env bash
# Builds a throwaway TableGrip profile with a seeded SQLite demo database, for taking product screenshots.
#
#   www/scripts/demo-profile.sh dark  [DIR]   # profile in DIR (default: www/.demo-profile), theme forced to Dark
#   www/scripts/demo-profile.sh light [DIR]
#
# Then launch the app against it:  scripts/run.sh --home DIR
set -euo pipefail

theme="${1:-dark}"
case "$theme" in dark) mode="Dark" ;; light) mode="Light" ;; *) echo "theme must be dark or light" >&2; exit 2 ;; esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
home="${2:-$root/www/.demo-profile}"
mkdir -p "$home"
db="$home/shop.sqlite"

if [[ ! -f "$db" ]]; then
  python3 - "$db" <<'PY'
import random, sqlite3, sys, datetime as dt
random.seed(7)
con = sqlite3.connect(sys.argv[1])
con.executescript("""
CREATE TABLE customers(id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE, full_name TEXT NOT NULL,
  country TEXT, plan TEXT, created_at TEXT NOT NULL);
CREATE TABLE products(id INTEGER PRIMARY KEY, sku TEXT UNIQUE, name TEXT NOT NULL, price NUMERIC NOT NULL,
  stock INTEGER NOT NULL DEFAULT 0, attributes TEXT);
CREATE TABLE orders(id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL REFERENCES customers(id),
  status TEXT NOT NULL, total NUMERIC NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE order_items(id INTEGER PRIMARY KEY, order_id INTEGER NOT NULL REFERENCES orders(id),
  product_id INTEGER NOT NULL REFERENCES products(id), quantity INTEGER NOT NULL, unit_price NUMERIC NOT NULL);
CREATE TABLE activity_log(id INTEGER PRIMARY KEY, customer_id INTEGER REFERENCES customers(id), event TEXT,
  payload TEXT, created_at TEXT);
CREATE INDEX orders_customer ON orders(customer_id);
CREATE INDEX orders_status ON orders(status, created_at);
CREATE VIEW revenue_by_day AS SELECT date(created_at) AS day, count(*) AS orders, sum(total) AS revenue
  FROM orders WHERE status = 'paid' GROUP BY 1;
""")
first = "Ava Liam Mia Noah Emma Lucas Chloe Minh Linh Hana Kai Leo Sofia Yuki Omar Zoe Ivan Nora Theo Mai".split()
last = "Nguyen Tran Smith Garcia Tanaka Silva Rossi Muller Kim Patel Novak Haddad Larsen Dubois".split()
countries = ["VN", "US", "DE", "JP", "BR", "FR", "GB", "SG", "CA", "AU"]
start = dt.datetime(2026, 1, 1)
for i in range(1, 241):
    f, l = random.choice(first), random.choice(last)
    con.execute("INSERT INTO customers VALUES(?,?,?,?,?,?)", (i, f"{f.lower()}.{l.lower()}{i}@example.com", f"{f} {l}",
        random.choice(countries), random.choice(["free", "free", "pro", "team"]),
        (start + dt.timedelta(hours=random.randint(0, 6000))).isoformat(sep=" ", timespec="seconds")))
names = ["Mechanical Keyboard", "USB-C Hub", "4K Monitor", "Desk Lamp", "Laptop Stand", "Noise Cancelling Headphones",
         "Webcam HD", "Ergonomic Mouse", "Standing Desk", "Cable Organizer", "Monitor Arm", "Wireless Charger"]
for i, n in enumerate(names, 1):
    con.execute("INSERT INTO products VALUES(?,?,?,?,?,?)", (i, f"SKU-{1000+i}", n, round(random.uniform(9, 420), 2),
        random.randint(0, 300), '{"color": "%s", "warranty_months": %d, "tags": ["desk", "setup"]}' % (random.choice(["black", "white", "graphite"]), random.choice([6, 12, 24]))))
statuses = ["paid"] * 6 + ["pending", "refunded", "shipped", "shipped"]
for i in range(1, 1201):
    con.execute("INSERT INTO orders VALUES(?,?,?,?,?)", (259000 + i, random.randint(1, 240), random.choice(statuses),
        round(random.uniform(12, 480), 2), (start + dt.timedelta(minutes=random.randint(0, 440000))).isoformat(sep=" ", timespec="seconds")))
    for _ in range(random.randint(1, 3)):
        con.execute("INSERT INTO order_items(order_id, product_id, quantity, unit_price) VALUES(?,?,?,?)",
            (259000 + i, random.randint(1, 12), random.randint(1, 4), round(random.uniform(9, 420), 2)))
for i in range(1, 400):
    con.execute("INSERT INTO activity_log(customer_id, event, payload, created_at) VALUES(?,?,?,?)",
        (random.randint(1, 240), random.choice(["login", "checkout", "plan_changed", "export"]),
         '{"ip": "10.0.%d.%d", "agent": "desktop"}' % (random.randint(0, 9), random.randint(1, 254)),
         (start + dt.timedelta(minutes=random.randint(0, 440000))).isoformat(sep=" ", timespec="seconds")))
con.commit()
PY
fi

cat > "$home/connections.json" <<JSON
[
  {"id": "demo-shop", "name": "shop (local)", "type": "SQLite", "database": "$db", "driver": "SQLite", "environment": "Local"},
  {"id": "demo-analytics", "name": "analytics", "type": "SQLite", "database": "$db", "driver": "SQLite", "environment": "Staging"},
  {"id": "demo-prod", "name": "shop-production", "type": "PostgreSQL", "host": "db.internal", "port": 5432, "database": "shop", "user": "readonly", "environment": "Production", "readOnly": true},
  {"id": "demo-cache", "name": "sessions-cache", "type": "Redis", "host": "localhost", "port": 6379, "environment": "Development"}
]
JSON

cat > "$home/settings.json" <<JSON
{ "theme": { "mode": "$mode" }, "pageSize": 500, "autoDownloadDrivers": true, "zoom": ${TABLEGRIP_ZOOM:-110},
  "agent": "cli:claude", "startupAgent": "LastUsed" }
JSON

# A finished chat with the agent, reopened by the session the capture script writes.
mkdir -p "$home/chats"
python3 - "$home/chats/demo-chat.json" <<'PY2'
import json, sys, time
now = int(time.time() * 1000)
view = """CREATE VIEW churned_after_refund AS
SELECT c.id, c.full_name, c.plan,
       max(o.created_at) AS refunded_at
FROM customers c
JOIN orders o ON o.customer_id = c.id
 AND o.status = 'refunded'
WHERE NOT EXISTS (
  SELECT 1 FROM orders later
  WHERE later.customer_id = c.id
    AND later.created_at > o.created_at)
GROUP BY c.id;"""
chat = {
  "id": "demo-chat", "title": "Customers lost after a refund", "createdAt": now - 600000, "updatedAt": now,
  "agent": "cli:claude",
  "messages": [
    {"role": "User", "text": "Which customers got a refund and never ordered again? Keep it as a view for the team."},
    {"role": "Assistant", "thoughtSeconds": 4,
     "activities": [{"id": "a1", "label": "Read schema of customers, orders", "state": "Done"}],
     "text": "A customer is lost when their **latest** order was refunded: 21 of them in `shop`, 9 on **pro** or **team**. This view changes the schema, so it is yours to run:\n\n```sql\n" + view + "\n```"},
  ],
}
json.dump(chat, open(sys.argv[1], "w"))
PY2

# Reuse drivers already downloaded on this machine so the demo connects offline.
if [[ -d "$HOME/.tablegrip/drivers" && ! -e "$home/drivers" ]]; then cp -R "$HOME/.tablegrip/drivers" "$home/drivers"; fi
echo "$home"
