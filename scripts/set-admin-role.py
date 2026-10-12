"""Set an existing local Compose account's role using operator DB access, never a web endpoint."""
import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--email", required=True)
    parser.add_argument("--role", choices=["USER", "ADMIN"], required=True)
    parser.add_argument("--confirm-role-change", action="store_true")
    args = parser.parse_args()
    email = args.email.strip().lower()
    if (not args.confirm_role_change or not args.project.startswith("order-stock-")
            or "@" not in email or len(email) > 254 or (args.env_file and not args.env_file.is_file())):
        parser.error("Specify an existing account, order-stock-* local project, private env file if used, and --confirm-role-change")
    root = Path(__file__).resolve().parents[1]
    command = ["docker", "compose", "-p", args.project]
    if args.env_file:
        command += ["--env-file", str(args.env_file.resolve())]
    command += ["-f", str(root / "compose.yaml"), "exec", "-T", "db", "sh", "-c",
                'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 '
                '--user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names']
    # A hex UTF-8 literal avoids shell/SQL interpolation of operator input.
    literal = "CONVERT(0x" + email.encode("utf-8").hex() + " USING utf8mb4) COLLATE utf8mb4_0900_as_cs"
    def sql(statement):
        result = subprocess.run(command, input=statement + "\n", text=True, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError("Role change could not access the selected local DB")
        return result.stdout.strip()
    if sql("SELECT DATABASE();") != "order_stock":
        raise RuntimeError("Only the local order_stock database is supported")
    user_id = sql(f"SELECT id FROM app_users WHERE email={literal};")
    if not user_id.isdecimal():
        raise RuntimeError("Register the account first; no account was changed")
    sql(f"UPDATE app_users SET user_role='{args.role}' WHERE id={int(user_id)};")
    if sql(f"SELECT user_role FROM app_users WHERE id={int(user_id)};") != args.role:
        raise RuntimeError("Role verification failed")
    print(f"PASS: account #{user_id} role={args.role}. Sign out and sign in again to refresh the session.")


if __name__ == "__main__":
    main()
