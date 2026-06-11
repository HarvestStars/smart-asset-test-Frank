from __future__ import annotations

import argparse
import threading
from datetime import datetime, timedelta

from .api import SmartAssetClient
from .dashboard import run_dashboard
from .market import RandomMarketGenerator
from .scenarios import SCENARIOS, run_scenario


def next_two_quarters() -> list[datetime]:
    now = datetime.now().replace(second=0, microsecond=0)
    minutes_to_next = 15 - (now.minute % 15)
    first = now + timedelta(minutes=minutes_to_next)
    return [first, first + timedelta(minutes=15)]


def parse_quarter(value: str) -> datetime:
    try:
        return datetime.fromisoformat(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            "quarter must be ISO local datetime, e.g. 2026-06-11T08:00:00"
        ) from exc


def add_connection_argument(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--base-url",
        default="http://localhost:8080",
        help="Kotlin service URL (default: %(default)s)",
    )


def add_quarter_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--quarter",
        action="append",
        type=parse_quarter,
        dest="quarters",
        help="quarter start; pass exactly twice (default: next two quarters)",
    )


def selected_quarters(args: argparse.Namespace) -> list[datetime]:
    quarters = args.quarters or next_two_quarters()
    if len(quarters) != 2:
        raise SystemExit("Pass --quarter exactly twice")
    return sorted(quarters)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m simulator",
        description="Market generator, dashboard and scenarios for smart-asset.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    generate = subparsers.add_parser("generate", help="run random market flow")
    add_connection_argument(generate)
    add_quarter_arguments(generate)
    generate.add_argument("--interval", type=float, default=1.5)
    generate.add_argument("--seed", type=int)
    generate.add_argument("--max-orders", type=int)
    generate.add_argument("--no-seed-depth", action="store_true")

    dashboard = subparsers.add_parser("dashboard", help="serve order-book monitor")
    add_connection_argument(dashboard)
    add_quarter_arguments(dashboard)
    dashboard.add_argument("--host", default="127.0.0.1")
    dashboard.add_argument("--port", type=int, default=8090)
    dashboard.add_argument("--refresh", type=float, default=1.0)

    combined = subparsers.add_parser(
        "all",
        help="run random generator and dashboard together",
    )
    add_connection_argument(combined)
    add_quarter_arguments(combined)
    combined.add_argument("--interval", type=float, default=1.5)
    combined.add_argument("--seed", type=int)
    combined.add_argument("--host", default="127.0.0.1")
    combined.add_argument("--port", type=int, default=8090)
    combined.add_argument("--refresh", type=float, default=1.0)

    scenario = subparsers.add_parser("scenario", help="run controlled validation")
    add_connection_argument(scenario)
    scenario.add_argument("name", choices=sorted(SCENARIOS))

    return parser


def main() -> None:
    args = build_parser().parse_args()
    client = SmartAssetClient(args.base_url)
    client.health()

    if args.command == "generate":
        quarters = selected_quarters(args)
        generator = RandomMarketGenerator(
            client,
            quarters,
            interval_seconds=args.interval,
            seed=args.seed,
        )
        if not args.no_seed_depth:
            generator.seed_books()
        generator.run(max_orders=args.max_orders)
        return

    if args.command == "dashboard":
        run_dashboard(
            client,
            selected_quarters(args),
            host=args.host,
            port=args.port,
            refresh_seconds=args.refresh,
        )
        return

    if args.command == "all":
        quarters = selected_quarters(args)
        stop_event = threading.Event()
        generator = RandomMarketGenerator(
            client,
            quarters,
            interval_seconds=args.interval,
            seed=args.seed,
        )
        generator.seed_books()
        thread = threading.Thread(
            target=generator.run,
            kwargs={"stop_event": stop_event},
            daemon=True,
        )
        thread.start()
        try:
            run_dashboard(
                client,
                quarters,
                host=args.host,
                port=args.port,
                refresh_seconds=args.refresh,
            )
        finally:
            stop_event.set()
            thread.join(timeout=3)
        return

    run_scenario(client, args.name)
