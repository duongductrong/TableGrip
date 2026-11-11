#!/usr/bin/env python3
"""
Exports the database logos the app and the website show, from a Simple Icons npm package.

    npm pack simple-icons@16.37.0 && tar xzf simple-icons-16.37.0.tgz
    python3 assets/branding/tools/export_db_logos.py package

Simple Icons' SVG data is CC0 1.0; the marks are trademarks of their owners (see NOTICE). Each path and its official
colour are kept as published. The only changes follow the usual brand rules for backgrounds: a mark too dark to read
on the dark theme is drawn white, and one too pale for the light theme sits on a black tile, as ClickHouse and DuckDB
publish theirs. Engines without a mark here (SQL Server, Oracle, …) keep their initials badge.
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP_OUT = ROOT / "app/src/main/resources/icons/db"
WWW_OUT = ROOT / "www/src/content/db-logos.ts"

# DatabaseType.id -> Simple Icons slug.
ENGINES = {
    "PostgreSQL": "postgresql",
    "MySQL": "mysql",
    "MariaDB": "mariadb",
    "SQLite": "sqlite",
    "ClickHouse": "clickhouse",
    "DuckDB": "duckdb",
    "MongoDB": "mongodb",
    "Redis": "redis",
    "CockroachDB": "cockroachlabs",
    "TimescaleDB": "timescale",
    "Neon": "neon",
    "Supabase": "supabase",
    "TiDB": "tidb",
    "PlanetScale": "planetscale",
    "Trino": "trino",
    "Presto": "presto",
    "Doris": "apachedoris",
    "OceanBase": "oceanbase",
    "Snowflake": "snowflake",
    "Databricks": "databricks",
    "BigQuery": "googlebigquery",
    "Spanner": "googlecloudspanner",
    "Hana": "sap",
    "Hive": "apachehive",
    "SparkSql": "apachespark",
    "Etcd": "etcd",
    "FerretDB": "ferretdb",
    "H2": "h2database",
    "JsonFile": "json",
    "Elasticsearch": "elasticsearch",
    "OpenSearch": "opensearch",
    "Meilisearch": "meilisearch",
    "Cassandra": "apachecassandra",
    "ScyllaDB": "scylladb",
    "CouchDB": "apachecouchdb",
    "InfluxDB": "influxdb",
    "VictoriaMetrics": "victoriametrics",
    "Neo4j": "neo4j",
    "Prometheus": "prometheus",
    "InfluxDB3": "influxdb",
    "Solr": "apachesolr",
    "Qdrant": "qdrant",
    "RabbitMQ": "rabbitmq",
    "Kafka": "apachekafka",
    "Consul": "consul",
}

# The website lists engines by display name.
WWW_NAMES = {
    "Etcd": "etcd",
    "H2": "H2",
    "JsonFile": "JSON",
    "Doris": "Apache Doris",
    "BigQuery": "Google BigQuery",
    "Spanner": "Cloud Spanner",
    "Hana": "SAP HANA",
    "Hive": "Apache Hive",
    "SparkSql": "Spark SQL",
    "Cassandra": "Apache Cassandra",
    "CouchDB": "Apache CouchDB",
    "InfluxDB": "InfluxDB 2",
    "InfluxDB3": "InfluxDB 3",
    "Solr": "Apache Solr",
    "Kafka": "Apache Kafka",
}

LIGHT_BG = "FFFFFF"
DARK_BG = "2B2D30"


def luminance(hex_):
    def channel(c):
        c = int(c, 16) / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (channel(hex_[i : i + 2]) for i in (0, 2, 4))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = sorted((luminance(a), luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def svg(path, fill, tile=None):
    if tile:
        body = f'<rect width="24" height="24" rx="5" fill="#{tile}"/><path transform="translate(4 4) scale(.6667)" fill="#{fill}" d="{path}"/>'
    else:
        body = f'<path fill="#{fill}" d="{path}"/>'
    return f'<svg width="16" height="16" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">{body}</svg>\n'


def main(package):
    package = Path(package)
    icons = json.loads((package / "data/simple-icons.json").read_text())
    version = json.loads((package / "package.json").read_text())["version"]
    APP_OUT.mkdir(parents=True, exist_ok=True)
    www = []
    for engine, slug in ENGINES.items():
        source = (package / "icons" / f"{slug}.svg").read_text()
        path = source.split(' d="', 1)[1].split('"', 1)[0]
        meta = next(i for i in icons if i.get("slug", slugify(i["title"])) == slug)
        hex_ = meta["hex"].upper()
        light_tile = "000000" if contrast(hex_, LIGHT_BG) < 1.6 else None
        dark_fill = "FFFFFF" if contrast(hex_, DARK_BG) < 2.0 else hex_
        (APP_OUT / f"{engine}.svg").write_text(svg(path, hex_, light_tile))
        (APP_OUT / f"{engine}_dark.svg").write_text(svg(path, dark_fill))
        www.append(f'  "{WWW_NAMES.get(engine, engine)}": {{ path: "{path}", hex: "#{hex_}", light: {"true" if light_tile else "false"}, dark: {"true" if dark_fill == "FFFFFF" else "false"} }},')
    WWW_OUT.write_text(
        "/**\n"
        f" * Database logos from Simple Icons {version} (https://simpleicons.org), whose SVG data is released under CC0 1.0.\n"
        " * The marks are trademarks of their owners, shown only to say which databases TableGrip connects to; TableGrip\n"
        " * is not affiliated with or endorsed by them (see NOTICE). Each is drawn as published, in its official colour:\n"
        " * `light` marks are too pale for a light page and sit on a black tile, as their owners publish them; `dark` marks\n"
        " * are too dark for a dark page and are drawn white there. Generated by assets/branding/tools/export_db_logos.py.\n"
        " * SQL Server, Oracle and the others without a mark here keep a text monogram.\n"
        " */\n"
        "export type DbLogo = { path: string; hex: string; light: boolean; dark: boolean }\n\n"
        "export const dbLogos: Record<string, DbLogo> = {\n" + "\n".join(www) + "\n}\n"
    )
    print(f"{len(ENGINES)} logos from Simple Icons {version}")


def slugify(title):
    # Simple Icons' own rule, enough for the titles above.
    return "".join(c for c in title.lower().replace("+", "plus").replace(".", "dot").replace("&", "and") if c.isalnum())


if __name__ == "__main__":
    main(sys.argv[1])
