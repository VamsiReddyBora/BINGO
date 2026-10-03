#!/usr/bin/env python3
"""
BINGO Multiplayer - Mobile Admin CLI (Termux / Linux / Ubuntu)
Management tool for players, live presence, matches, and database administration.
"""

import sys
import os
import time
import json
import base64
import getpass
import urllib.request
import urllib.parse
from datetime import datetime

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("\n\033[91m[ERROR] 'paho-mqtt' is not installed!\033[0m")
    print("Install it by running:")
    print("  pip install paho-mqtt")
    print("or:")
    print("  pip install --break-system-packages paho-mqtt\n")
    sys.exit(1)

# ─────────────────────────────────────────────────────────────
# CONFIGURATION
# ─────────────────────────────────────────────────────────────
MQTT_BROKER = "broker.emqx.io"
MQTT_PORT = 1883
KEYVALUE_API_URL = "https://keyvalue.immanuel.co/api/KeyVal"
KEYVALUE_APP_KEY = "2464j24f"

# Default Admin PIN (can also be set via BINGO_ADMIN_PIN environment variable)
ADMIN_PIN = os.environ.get("BINGO_ADMIN_PIN", "bingo2026")

# Global Cache
players = {}
presences = {}
rooms = {}

# ANSI Color Helpers
C_RESET = "\033[0m"
C_BOLD = "\033[1m"
C_CYAN = "\033[96m"
C_GREEN = "\033[92m"
C_YELLOW = "\033[93m"
C_RED = "\033[91m"
C_MAGENTA = "\033[95m"
C_GRAY = "\033[90m"
C_BLUE = "\033[94m"

# ─────────────────────────────────────────────────────────────
# DATA FETCHING ENGINE (MQTT + KeyVal)
# ─────────────────────────────────────────────────────────────

def fetch_live_data(timeout=2.0):
    global players, presences, rooms
    players.clear()
    presences.clear()
    rooms.clear()

    def on_connect(client, userdata, flags, rc, properties=None):
        client.subscribe("bingo/v3/#")

    def on_message(client, userdata, msg):
        topic = msg.topic
        payload = msg.payload.decode('utf-8', errors='ignore')
        if not payload:
            return

        if topic.startswith("bingo/v3/registry/"):
            username = topic.split("/")[-1].lower()
            try:
                players[username] = json.loads(payload)
            except Exception:
                pass
        elif topic.startswith("bingo/v3/presence/"):
            username = topic.split("/")[-1].lower()
            try:
                presences[username] = json.loads(payload)
            except Exception:
                pass
        elif topic.startswith("bingo/v3/room_meta/"):
            room_code = topic.split("/")[-1].upper()
            try:
                rooms[room_code] = json.loads(payload)
            except Exception:
                pass

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"admin_cli_{int(time.time())}")
    client.on_connect = on_connect
    client.on_message = on_message

    try:
        client.connect(MQTT_BROKER, MQTT_PORT, 60)
        client.loop_start()
        time.sleep(timeout)
        client.loop_stop()
        client.disconnect()
    except Exception as e:
        print(f"{C_RED}MQTT Connection failed: {e}{C_RESET}")

def fetch_keyval_user(username):
    clean = username.strip().lower().lstrip("@")
    try:
        url = f"{KEYVALUE_API_URL}/GetValue/{KEYVALUE_APP_KEY}/reg_{clean}"
        req = urllib.request.Request(url, headers={'User-Agent': 'BingoAdminCLI/1.0'})
        with urllib.request.urlopen(req, timeout=3) as resp:
            if resp.status == 200:
                raw = resp.read().decode('utf-8').strip().strip('"')
                if raw and raw != "null":
                    decoded = base64.b64decode(raw).decode('utf-8')
                    return json.loads(decoded)
    except Exception:
        pass
    return None

def clear_keyval_key(key):
    try:
        enc_key = urllib.parse.quote(key.strip())
        url = f"{KEYVALUE_API_URL}/UpdateValue/{KEYVALUE_APP_KEY}/{enc_key}?value="
        req = urllib.request.Request(url, data=b'', method='POST')
        req.add_header('Content-Length', '0')
        req.add_header('User-Agent', 'BingoAdminCLI/1.0')
        with urllib.request.urlopen(req, timeout=3) as resp:
            return resp.status == 200
    except Exception:
        return False

# ─────────────────────────────────────────────────────────────
# FORMATTING & BADGES
# ─────────────────────────────────────────────────────────────

def format_timestamp(ts_ms):
    if not ts_ms or ts_ms == 0:
        return f"{C_GRAY}Never / Offline{C_RESET}"
    try:
        dt = datetime.fromtimestamp(ts_ms / 1000.0)
        now = datetime.now()
        diff_sec = (now - dt).total_seconds()
        
        if diff_sec < 60:
            return f"{C_GREEN}Just now ({int(diff_sec)}s ago){C_RESET}"
        elif diff_sec < 3600:
            return f"{C_GREEN}{int(diff_sec // 60)} mins ago{C_RESET}"
        elif diff_sec < 86400:
            return f"{C_YELLOW}{int(diff_sec // 3600)}h ago ({dt.strftime('%H:%M')}){C_RESET}"
        else:
            return f"{C_GRAY}{dt.strftime('%Y-%m-%d %H:%M')}{C_RESET}"
    except Exception:
        return f"{C_GRAY}Unknown{C_RESET}"

def get_status_badge(status):
    status = (status or "OFFLINE").upper()
    if status == "ONLINE":
        return f"{C_GREEN}● ONLINE{C_RESET}"
    elif status == "IN_LOBBY":
        return f"{C_YELLOW}▲ IN LOBBY{C_RESET}"
    elif status == "PLAYING":
        return f"{C_CYAN}🎮 PLAYING{C_RESET}"
    elif status == "BANNED":
        return f"{C_RED}✖ BANNED{C_RESET}"
    else:
        return f"{C_GRAY}○ OFFLINE{C_RESET}"

# ─────────────────────────────────────────────────────────────
# CLI SCREENS & ACTIONS
# ─────────────────────────────────────────────────────────────

def print_banner():
    print(f"\n{C_BOLD}{C_CYAN}╔══════════════════════════════════════════════════════════╗{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║             BINGO MULTIPLAYER — ADMIN CLI                ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║            Real-Time Monitor & Player Control            ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}╚══════════════════════════════════════════════════════════╝{C_RESET}")

def show_all_users():
    all_users = sorted(set(list(players.keys()) + list(presences.keys())))
    if not all_users:
        print(f"\n{C_YELLOW}[!] No registered players found.{C_RESET}")
        return

    print(f"\n{C_BOLD}{C_MAGENTA}── ALL REGISTERED PLAYERS DIRECTORY ({len(all_users)}) ──{C_RESET}")
    header = f"{'Username':<14} {'Display Name':<14} {'Lvl':<5} {'Matches':<8} {'Wins':<6} {'Win%':<7} {'Streak':<7} {'Status':<14}"
    print(f"{C_BOLD}{header}{C_RESET}")
    print("─" * 80)

    for u in all_users:
        data = players.get(u, {})
        pres = presences.get(u, {})
        display = (data.get("displayName") or u)[:13]
        lvl = data.get("level", 1)
        played = data.get("gamesPlayed", 0)
        won = data.get("gamesWon", 0)
        streak = data.get("currentStreak", 0)
        win_rate = f"{(won / played * 100):.0f}%" if played > 0 else "0%"
        status = get_status_badge(pres.get("status", "OFFLINE"))

        print(f"@{u:<13} {display:<14} {lvl:<5} {played:<8} {won:<6} {win_rate:<7} {streak:<7} {status}")
    print("─" * 80)

def show_online_users():
    online_list = []
    for u, pres in presences.items():
        st = pres.get("status", "OFFLINE").upper()
        if st in ("ONLINE", "IN_LOBBY", "PLAYING"):
            online_list.append((u, st, pres.get("timestamp", 0)))

    print(f"\n{C_BOLD}{C_GREEN}── ONLINE PLAYERS ({len(online_list)}) ──{C_RESET}")
    if not online_list:
        print(f"{C_GRAY}No players are currently online.{C_RESET}\n")
        return

    header = f"{'Username':<15} {'Display Name':<16} {'Activity':<16} {'Last Heartbeat':<20}"
    print(f"{C_BOLD}{header}{C_RESET}")
    print("─" * 70)

    for u, st, ts in online_list:
        p_data = players.get(u, {})
        disp = p_data.get("displayName") or u
        badge = get_status_badge(st)
        seen = format_timestamp(ts)
        print(f"@{u:<14} {disp:<16} {badge:<25} {seen}")
    print("─" * 70)

def delete_room(room_code):
    clean_code = room_code.strip().upper()
    if not clean_code:
        return
    try:
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"room_killer_{int(time.time())}")
        client.connect(MQTT_BROKER, MQTT_PORT, 60)
        client.publish(f"bingo/v3/room_meta/{clean_code}", b"", qos=1, retain=True)
        cancel_pkt = json.dumps({"type": "ROOM_CANCELLED", "roomCode": clean_code})
        client.publish(f"bingo/v3/room/{clean_code}", cancel_pkt, qos=1)
        time.sleep(0.5)
        client.disconnect()
        rooms.pop(clean_code, None)
        print(f"\n{C_GREEN}✓ Room {clean_code} closed and removed!{C_RESET}\n")
    except Exception as e:
        print(f"{C_RED}Failed to close room: {e}{C_RESET}")

def show_active_rooms(from_menu=False):
    print(f"\n{C_BOLD}{C_YELLOW}── ACTIVE GAME ROOMS ({len(rooms)}) ──{C_RESET}")
    if not rooms:
        print(f"{C_GRAY}No active game rooms found.{C_RESET}\n")
        return

    header = f"{'Room Code':<10} {'Host':<16} {'State':<12} {'Joined Players':<28} {'Grid':<6}"
    print(f"{C_BOLD}{header}{C_RESET}")
    print("─" * 75)

    for code, r in rooms.items():
        host = f"@{r.get('hostUsername', 'unknown')}"
        state = r.get("status", "UNKNOWN")
        plist = r.get("players", [])
        if isinstance(plist, list):
            names = ", ".join([f"@{p.get('username', p.get('displayName', 'p'))}" for p in plist])
            p_str = f"({len(plist)}) {names}"[:27]
        else:
            p_str = "0 players"
        grid = f"{r.get('boardSize', 5)}x{r.get('boardSize', 5)}"
        print(f"{code:<10} {host:<16} {state:<12} {p_str:<28} {grid:<6}")
    print("─" * 75)

    if from_menu and sys.stdin.isatty():
        sub = input(f"{C_YELLOW}Options: [D] Delete/Close a Room | [Enter] Back to Menu: {C_RESET}").strip().lower()
        if sub == "d":
            target_code = input("Enter Room Code to close: ").strip()
            delete_room(target_code)

def inspect_player(target_username=None, from_menu=False):
    if not target_username:
        raw_user = input(f"\n{C_BOLD}Enter username to inspect (e.g. @bob): {C_RESET}").strip()
        target_username = raw_user.lstrip("@").lower()

    clean = target_username.strip().lower()
    if not clean:
        return

    p_data = players.get(clean)
    pres = presences.get(clean, {})

    # If not found in MQTT cache, query cloud KeyValue store directly
    if not p_data:
        print(f"{C_GRAY}Querying Cloud KeyValue store for @{clean}...{C_RESET}")
        p_data = fetch_keyval_user(clean)

    if not p_data and not pres:
        print(f"{C_RED}[!] Player '@{clean}' was not found in MQTT registry or cloud database.{C_RESET}")
        return

    display_name = p_data.get("displayName", clean) if p_data else clean
    uid = p_data.get("uid", "None") if p_data else "Unknown"
    level = p_data.get("level", 1) if p_data else 1
    played = p_data.get("gamesPlayed", 0) if p_data else 0
    won = p_data.get("gamesWon", 0) if p_data else 0
    lost = max(0, played - won)
    streak = p_data.get("currentStreak", 0) if p_data else 0
    win_rate = f"{(won / played * 100):.1f}%" if played > 0 else "0.0%"
    status = pres.get("status", "OFFLINE")
    last_seen_ts = pres.get("timestamp") or (p_data.get("lastSeenTimestamp") if p_data else 0)

    print(f"\n{C_BOLD}{C_CYAN}╔═══════════════════════════════════════════════════╗{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║               PLAYER PROFILE CARD                 ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}╚═══════════════════════════════════════════════════╝{C_RESET}")
    print(f"  {C_BOLD}Username:{C_RESET}       @{clean}")
    print(f"  {C_BOLD}Display Name:{C_RESET}   {display_name}")
    print(f"  {C_BOLD}Status:{C_RESET}         {get_status_badge(status)}")
    print(f"  {C_BOLD}Last Seen:{C_RESET}      {format_timestamp(last_seen_ts)}")
    print(f"  {C_BOLD}User ID (UID):{C_RESET}  {uid}")
    print("  " + "─" * 45)
    print(f"  {C_BOLD}Player Level:{C_RESET}   Level {level}")
    print(f"  {C_BOLD}Matches Played:{C_RESET} {played}")
    print(f"  {C_BOLD}Matches Won:{C_RESET}    {won}")
    print(f"  {C_BOLD}Matches Lost:{C_RESET}   {lost}")
    print(f"  {C_BOLD}Win Percentage:{C_RESET} {win_rate}")
    print(f"  {C_BOLD}Current Streak:{C_RESET} {streak} wins in a row")
    print("  " + "─" * 45)

    if from_menu and sys.stdin.isatty():
        sub_action = input(f"{C_YELLOW}Options: [K] Kick/Remove this player | [Enter] Back to Menu: {C_RESET}").strip().lower()
        if sub_action == "k":
            remove_player(clean)


def remove_player(target_username=None):
    if not target_username:
        raw_user = input(f"\n{C_BOLD}{C_RED}Enter username to kick & delete (e.g. @hacker): {C_RESET}").strip()
        target_username = raw_user.lstrip("@").lower()

    clean = target_username.strip().lower()
    if not clean:
        print(f"{C_GRAY}Operation cancelled.{C_RESET}")
        return

    # Check existence
    p_data = players.get(clean) or fetch_keyval_user(clean)
    pres = presences.get(clean)

    if not p_data and not pres:
        print(f"\n{C_YELLOW}[!] Warning: Player '@{clean}' was not found in registry cache.")
        proceed = input(f"Do you still want to send wipe & kick commands for '@{clean}'? (yes/no): ").strip().lower()
        if proceed != "yes":
            return
    else:
        disp = p_data.get("displayName", clean) if p_data else clean
        uid = p_data.get("uid", "") if p_data else ""
        print(f"\n{C_BOLD}{C_RED}⚠️  TARGET FOR REMOVAL:{C_RESET} @{clean} ({disp}) | UID: {uid}")

    # Confirmation
    confirm = input(f"\n{C_BOLD}{C_RED}Are you SURE you want to permanently delete player @{clean}? (yes/no): {C_RESET}").strip().lower()
    if confirm != "yes":
        print(f"{C_GRAY}[Canceled] Player removal aborted.{C_RESET}")
        return

    # Authentication Password / PIN
    if sys.stdin.isatty():
        try:
            entered_pin = getpass.getpass(f"{C_BOLD}Enter Admin PIN to authenticate: {C_RESET}").strip()
        except Exception:
            entered_pin = input(f"{C_BOLD}Enter Admin PIN to authenticate: {C_RESET}").strip()
    else:
        entered_pin = input(f"{C_BOLD}Enter Admin PIN to authenticate: {C_RESET}").strip()

    if entered_pin != ADMIN_PIN:
        print(f"\n{C_RED}[ACCESS DENIED] Incorrect Admin PIN! Removal rejected.{C_RESET}\n")
        return

    print(f"\n{C_YELLOW}Executing kick & wipe protocol for @{clean}...{C_RESET}")

    # 1. MQTT WIPE & KICK
    try:
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"admin_killer_{int(time.time())}")
        client.connect(MQTT_BROKER, MQTT_PORT, 60)

        # Clear retained registry by publishing empty payload with retain=True
        client.publish(f"bingo/v3/registry/{clean}", b"", qos=1, retain=True)

        # Mark presence as BANNED / OFFLINE with retain=True
        banned_payload = json.dumps({"username": clean, "status": "OFFLINE", "timestamp": 0})
        client.publish(f"bingo/v3/presence/{clean}", banned_payload, qos=1, retain=True)

        # If UID/Google ID is known, send immediate force logout broadcast to their device
        uid = (p_data.get("uid") or "") if p_data else ""
        if uid:
            client.publish(f"bingo/v3/google_account/{uid}", b"", qos=1, retain=True)
            force_logout_pkt = json.dumps({
                "type": "FORCE_LOGOUT",
                "googleId": uid,
                "deviceId": "admin_cli",
                "deviceModel": "Admin Console"
            })
            client.publish(f"bingo/v3/auth_session/{uid}", force_logout_pkt, qos=1)

        # Broadcast kick to all active game rooms
        for r_code, r_data in rooms.items():
            joined_users = [p.get("username", "").lower() for p in r_data.get("players", [])]
            if clean in joined_users:
                kick_pkt = json.dumps({
                    "type": "KICK_PLAYER",
                    "targetPlayerId": clean,
                    "playerId": "ADMIN"
                })
                client.publish(f"bingo/v3/room/{r_code}", kick_pkt, qos=1)

        time.sleep(0.5)
        client.disconnect()
        print(f"  {C_GREEN}✓ Purged MQTT retained records & broadcasted force disconnect.{C_RESET}")
    except Exception as e:
        print(f"  {C_RED}✗ MQTT purge error: {e}{C_RESET}")

    # 2. CLOUD KEYVALUE WIPE
    try:
        clear_keyval_key(f"reg_{clean}")
        clear_keyval_key(f"pres_{clean}")
        clear_keyval_key(f"user_{clean}")
        print(f"  {C_GREEN}✓ Purged Cloud KeyValue database entries.{C_RESET}")
    except Exception as e:
        print(f"  {C_RED}✗ KeyValue purge error: {e}{C_RESET}")

    # 3. Clean local cache
    players.pop(clean, None)
    presences.pop(clean, None)

    print(f"\n{C_BOLD}{C_GREEN}🎉 SUCCESS: Player @{clean} has been kicked and wiped from the database!{C_RESET}\n")

# ─────────────────────────────────────────────────────────────
# MAIN MENU LOOP
# ─────────────────────────────────────────────────────────────

def main():
    # Direct CLI command line flags support for quick mobile use
    if len(sys.argv) > 1:
        arg = sys.argv[1].lower()
        if arg in ("-h", "--help"):
            print(f"\n{C_BOLD}Usage: bingo [options]{C_RESET}")
            print("  bingo                      Launch interactive admin menu")
            print("  bingo -u, --users          Show all players directory")
            print("  bingo -o, --online         Show online players")
            print("  bingo -r, --rooms          Show active game rooms")
            print("  bingo -i, --inspect <user> Inspect a specific player")
            print("  bingo -k, --kick <user>    Kick & delete a player\n")
            return

        print_banner()
        fetch_live_data(timeout=2.0)

        if arg in ("-u", "--users"):
            show_all_users()
        elif arg in ("-o", "--online"):
            show_online_users()
        elif arg in ("-r", "--rooms"):
            show_active_rooms()
        elif arg in ("-i", "--inspect"):
            target = sys.argv[2] if len(sys.argv) > 2 else ""
            inspect_player(target)
        elif arg in ("-k", "--kick"):
            target = sys.argv[2] if len(sys.argv) > 2 else ""
            remove_player(target)
        else:
            print(f"{C_RED}Unknown option '{arg}'. Run 'bingo --help' for options.{C_RESET}")
        return

    print_banner()
    print(f"{C_CYAN}Connecting to Bingo game server to load live data...{C_RESET}")
    fetch_live_data(timeout=2.0)
    print(f"{C_GREEN}Data synchronized ({len(players)} players, {len(rooms)} rooms).{C_RESET}")

    while True:
        print(f"\n{C_BOLD}────────────── MAIN MENU ──────────────{C_RESET}")
        print(f"  {C_BOLD}[1]{C_RESET} 📋 All Players Directory")
        print(f"  {C_BOLD}[2]{C_RESET} 🟢 Online Players Only")
        print(f"  {C_BOLD}[3]{C_RESET} 🎮 Active Game Matches / Rooms")
        print(f"  {C_BOLD}[4]{C_RESET} 🔍 Inspect Player Details & Stats")
        print(f"  {C_BOLD}[5]{C_RESET} 🚫 Kick & Delete Player from Database")
        print(f"  {C_BOLD}[6]{C_RESET} 🔄 Refresh Server Data")
        print(f"  {C_BOLD}[0]{C_RESET} 🚪 Exit")
        print("───────────────────────────────────────")

        choice = input(f"{C_BOLD}Select an option (0-6): {C_RESET}").strip()

        if choice == "1":
            show_all_users()
        elif choice == "2":
            show_online_users()
        elif choice == "3":
            show_active_rooms(from_menu=True)
        elif choice == "4":
            inspect_player(from_menu=True)
        elif choice == "5":
            remove_player()
        elif choice == "6":
            print(f"\n{C_CYAN}Refreshing live data from servers...{C_RESET}")
            fetch_live_data(timeout=2.0)
            print(f"{C_GREEN}Refreshed! ({len(players)} players, {len(rooms)} rooms){C_RESET}")
        elif choice in ("0", "exit", "quit", "q"):
            print(f"\n{C_CYAN}Goodbye! 👋{C_RESET}\n")
            break
        else:
            print(f"{C_RED}[!] Invalid choice. Please choose 0 to 6.{C_RESET}")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print(f"\n\n{C_YELLOW}Admin CLI exited.{C_RESET}\n")
