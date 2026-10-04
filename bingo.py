#!/usr/bin/env python3
"""
BINGO Multiplayer - Mobile Admin CLI (Termux / Linux / Ubuntu)
Real-time management tool for players, live presence, matches, and database administration.
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
from concurrent.futures import ThreadPoolExecutor

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
# DATA FETCHING ENGINE (Dual-Channel: MQTT + KeyVal Cloud)
# ─────────────────────────────────────────────────────────────

def fetch_keyval_user(username):
    clean = username.strip().lower().lstrip("@")
    for _ in range(2):
        try:
            url = f"{KEYVALUE_API_URL}/GetValue/{KEYVALUE_APP_KEY}/reg_{clean}"
            req = urllib.request.Request(url, headers={'User-Agent': 'BingoAdminCLI/2.0'})
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    raw = resp.read().decode('utf-8').strip().strip('"')
                    if not raw or raw == "null":
                        return {}  # Confirmed deleted / not found
                    decoded = base64.b64decode(raw).decode('utf-8')
                    return json.loads(decoded)
        except Exception:
            time.sleep(0.3)
    return None  # Transient network error

def fetch_keyval_presence(username):
    clean = username.strip().lower().lstrip("@")
    for _ in range(2):
        try:
            url = f"{KEYVALUE_API_URL}/GetValue/{KEYVALUE_APP_KEY}/pres_{clean}"
            req = urllib.request.Request(url, headers={'User-Agent': 'BingoAdminCLI/2.0'})
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    raw = resp.read().decode('utf-8').strip().strip('"')
                    if not raw or raw == "null":
                        return {}
                    if ":" in raw:
                        parts = raw.split(":", 1)
                        return {"username": clean, "status": parts[0], "timestamp": int(parts[1])}
        except Exception:
            time.sleep(0.3)
    return None

def fetch_keyval_directory():
    try:
        url = f"{KEYVALUE_API_URL}/GetValue/{KEYVALUE_APP_KEY}/user_directory"
        req = urllib.request.Request(url, headers={'User-Agent': 'BingoAdminCLI/2.0'})
        with urllib.request.urlopen(req, timeout=3) as resp:
            if resp.status == 200:
                raw = resp.read().decode('utf-8').strip().strip('"')
                if raw and raw != "null":
                    return [u.strip().lower() for u in raw.split(",") if u.strip()]
    except Exception:
        pass
    return []

def update_keyval_directory(user_list):
    try:
        val = ",".join(sorted(set([u.strip().lower() for u in user_list if u.strip()])))
        enc_val = urllib.parse.quote(val)
        url = f"{KEYVALUE_API_URL}/UpdateValue/{KEYVALUE_APP_KEY}/user_directory?value={enc_val}"
        req = urllib.request.Request(url, data=b'', method='POST')
        req.add_header('Content-Length', '0')
        req.add_header('User-Agent', 'BingoAdminCLI/2.0')
        with urllib.request.urlopen(req, timeout=3) as resp:
            return resp.status == 200
    except Exception:
        return False

def clear_keyval_key(key):
    try:
        enc_key = urllib.parse.quote(key.strip())
        url = f"{KEYVALUE_API_URL}/UpdateValue/{KEYVALUE_APP_KEY}/{enc_key}?value="
        req = urllib.request.Request(url, data=b'', method='POST')
        req.add_header('Content-Length', '0')
        req.add_header('User-Agent', 'BingoAdminCLI/2.0')
        with urllib.request.urlopen(req, timeout=3) as resp:
            return resp.status == 200
    except Exception:
        return False

def fetch_keyval_friends(username):
    clean = username.strip().lower().lstrip("@")
    try:
        url = f"{KEYVALUE_API_URL}/GetValue/{KEYVALUE_APP_KEY}/friends_{clean}"
        req = urllib.request.Request(url, headers={'User-Agent': 'BingoAdminCLI/2.0'})
        with urllib.request.urlopen(req, timeout=3) as resp:
            if resp.status == 200:
                raw = resp.read().decode('utf-8').strip().strip('"')
                if raw and raw != "null":
                    decoded = base64.b64decode(raw).decode('utf-8')
                    flist = json.loads(decoded)
                    return [f.get("username", "").strip().lower() for f in flist if f.get("username")]
    except Exception:
        pass
    return []

def fetch_player_cloud_backup(username):
    clean = username.strip().lower().lstrip("@")
    bin_id = None
    try:
        url = f"{KEYVALUE_API_URL}/GetValue/{KEYVALUE_APP_KEY}/user_{clean}"
        req = urllib.request.Request(url, headers={'User-Agent': 'BingoAdminCLI/2.0'})
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                raw = resp.read().decode('utf-8').strip().strip('"')
                if raw and raw != "null":
                    bin_id = raw
    except Exception:
        pass

    if not bin_id:
        p_data = fetch_keyval_user(clean)
        uid = p_data.get("uid") if isinstance(p_data, dict) else None
        if uid:
            clean_uid = uid.replace("google_", "")
            try:
                url = f"{KEYVALUE_API_URL}/GetValue/{KEYVALUE_APP_KEY}/gid_{clean_uid}"
                req = urllib.request.Request(url, headers={'User-Agent': 'BingoAdminCLI/2.0'})
                with urllib.request.urlopen(req, timeout=5) as resp:
                    if resp.status == 200:
                        raw = resp.read().decode('utf-8').strip().strip('"')
                        if raw and raw != "null":
                            bin_id = raw
            except Exception:
                pass

    if not bin_id:
        return None, None

    try:
        bin_url = f"https://extendsclass.com/api/json-storage/bin/{bin_id}"
        req = urllib.request.Request(bin_url, headers={'User-Agent': 'BingoAdminCLI/2.0'})
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode('utf-8'))
                return bin_id, data
    except Exception:
        pass

    return bin_id, None

def save_player_cloud_backup(bin_id, data):
    try:
        bin_url = f"https://extendsclass.com/api/json-storage/bin/{bin_id}"
        payload = json.dumps(data).encode('utf-8')
        req = urllib.request.Request(bin_url, data=payload, method='PUT', headers={
            'Content-Type': 'application/json',
            'User-Agent': 'BingoAdminCLI/2.0'
        })
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status == 200
    except Exception:
        return False

def fetch_player_friends_details(username):
    clean = username.strip().lower().lstrip("@")
    try:
        url = f"{KEYVALUE_API_URL}/GetValue/{KEYVALUE_APP_KEY}/friends_{clean}"
        req = urllib.request.Request(url, headers={'User-Agent': 'BingoAdminCLI/2.0'})
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                raw = resp.read().decode('utf-8').strip().strip('"')
                if raw and raw != "null":
                    decoded = base64.b64decode(raw).decode('utf-8')
                    return json.loads(decoded)
    except Exception:
        pass
    return []

def save_player_friends_details(username, friends_list):
    clean = username.strip().lower().lstrip("@")
    try:
        if not friends_list:
            return clear_keyval_key(f"friends_{clean}")
        json_str = json.dumps(friends_list)
        b64 = base64.b64encode(json_str.encode('utf-8')).decode('utf-8')
        enc_val = urllib.parse.quote(b64)
        url = f"{KEYVALUE_API_URL}/UpdateValue/{KEYVALUE_APP_KEY}/friends_{clean}?value={enc_val}"
        req = urllib.request.Request(url, data=b'', method='POST', headers={
            'Content-Length': '0',
            'User-Agent': 'BingoAdminCLI/2.0'
        })
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status == 200
    except Exception:
        return False


def fetch_live_data(timeout=2.5):
    """
    Dual-channel real-time fetch:
    1. Subscribes to MQTT broker for live heartbeats, registrations, and room activity.
    2. Synchronizes with KeyValue cloud database to discover all registered accounts.
    3. Reconciles player stats and strictly computes live status using heartbeat freshness.
    """
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
        elif topic.startswith("bingo/v3/google_account/"):
            try:
                data = json.loads(payload)
                u = data.get("username", "").strip().lower()
                if u and (u not in players or players[u].get("gamesPlayed", 0) == 0):
                    players[u] = data
            except Exception:
                pass
        elif topic.startswith("bingo/v3/room_meta/"):
            room_code = topic.split("/")[-1].upper()
            try:
                r = json.loads(payload)
                rooms[room_code] = r
                for p in r.get("players", []):
                    pu = p.get("username", "").strip().lower()
                    if pu and pu not in players:
                        players[pu] = p
            except Exception:
                pass

    # 1. Start MQTT background listener
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"admin_cli_{int(time.time())}")
    client.on_connect = on_connect
    client.on_message = on_message

    try:
        client.connect(MQTT_BROKER, MQTT_PORT, 60)
        client.loop_start()
    except Exception as e:
        print(f"{C_RED}MQTT Connection warning: {e}{C_RESET}")
        client = None

    # 2. Concurrently fetch cloud user directory
    cloud_users = fetch_keyval_directory()

    # Wait for MQTT initial messages
    time.sleep(timeout)
    if client:
        try:
            client.loop_stop()
            client.disconnect()
        except Exception:
            pass

    # 3. Consolidate all known usernames
    all_known_users = sorted(set(list(players.keys()) + list(presences.keys()) + cloud_users))

    # 4. Multi-threaded parallel fetch from KeyValue for any user missing details or presence
    def hydrate_user(u):
        p_data = None
        pr_data = None
        flist = []
        # If user data missing or has 0 games, try KeyValue
        if u not in players or players[u].get("gamesPlayed", 0) == 0:
            p_data = fetch_keyval_user(u)
        
        # If presence missing or timestamp is 0, try KeyValue pres_<user>
        if u not in presences or presences[u].get("timestamp", 0) == 0:
            pr_data = fetch_keyval_presence(u)
            
        flist = fetch_keyval_friends(u)
        return u, p_data, pr_data, flist

    discovered_friends = set()
    pruned_deleted = set()

    with ThreadPoolExecutor(max_workers=6) as executor:
        for u, p_data, pr_data, flist in executor.map(hydrate_user, all_known_users):
            if isinstance(p_data, dict) and p_data.get("username"):
                existing = players.get(u, {})
                if p_data.get("gamesPlayed", 0) >= existing.get("gamesPlayed", 0):
                    players[u] = p_data
            elif p_data == {} and (pr_data == {} or pr_data is None) and u not in players:
                # Confirmed deleted on the server (key returned empty string)
                pruned_deleted.add(u)
                
            if isinstance(pr_data, dict) and pr_data.get("status"):
                existing_ts = presences.get(u, {}).get("timestamp", 0)
                if pr_data.get("timestamp", 0) > existing_ts:
                    presences[u] = pr_data

            for fu in flist:
                if fu and fu not in all_known_users:
                    discovered_friends.add(fu)

    # 4b. Hydrate any newly discovered friends from social connections
    if discovered_friends:
        with ThreadPoolExecutor(max_workers=6) as executor:
            for fu, fp_data, fpr_data, _ in executor.map(hydrate_user, list(discovered_friends)):
                if isinstance(fp_data, dict) and fp_data.get("username"):
                    players[fu] = fp_data
                if isinstance(fpr_data, dict) and fpr_data.get("status"):
                    presences[fu] = fpr_data

    # 5. Remove pruned/deleted users from local state
    for du in pruned_deleted:
        players.pop(du, None)
        presences.pop(du, None)

    # 6. Reconcile verified active users with cloud directory
    active_user_set = sorted(set([u for u in (list(players.keys()) + list(presences.keys())) if u not in pruned_deleted]))
    if active_user_set and set(active_user_set) != set(cloud_users):
        update_keyval_directory(active_user_set)

# ─────────────────────────────────────────────────────────────
# LIVE PRESENCE & STATUS RESOLVER
# ─────────────────────────────────────────────────────────────

def get_user_status(username):
    """
    Calculates live presence status strictly using timestamp freshness:
    - ONLINE / IN_LOBBY / PLAYING only if last heartbeat was received within 20 seconds.
    - Otherwise, status is OFFLINE with humanized last seen duration.
    """
    pres = presences.get(username.lower(), {})
    st = (pres.get("status") or "OFFLINE").upper()
    ts = pres.get("timestamp", 0)
    now_ms = time.time() * 1000
    diff_sec = (now_ms - ts) / 1000.0 if ts > 0 else 999999.0

    is_live = (diff_sec < 20.0) and (st in ("ONLINE", "IN_LOBBY", "PLAYING"))
    if is_live:
        return st, ts, diff_sec
    return "OFFLINE", ts, diff_sec

def format_time_ago(ts_ms):
    if not ts_ms or ts_ms == 0:
        return "Never"
    try:
        now_ms = time.time() * 1000
        diff_sec = max(0, (now_ms - ts_ms) / 1000.0)
        if diff_sec < 60:
            return "just now"
        elif diff_sec < 3600:
            return f"{int(diff_sec // 60)}m ago"
        elif diff_sec < 86400:
            return f"{int(diff_sec // 3600)}h ago"
        else:
            return f"{int(diff_sec // 86400)}d ago"
    except Exception:
        return "Unknown"

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

def get_status_badge(st, is_live=False, ts=0):
    st = (st or "OFFLINE").upper()
    if is_live:
        if st == "PLAYING":
            return f"{C_MAGENTA}🎮 PLAYING{C_RESET}"
        elif st == "IN_LOBBY":
            return f"{C_YELLOW}▲ IN LOBBY{C_RESET}"
        elif st == "ONLINE":
            return f"{C_GREEN}● ONLINE{C_RESET}"
        else:
            return f"{C_GREEN}● {st}{C_RESET}"
    else:
        if ts and ts > 0:
            return f"{C_GRAY}○ OFFLINE ({format_time_ago(ts)}){C_RESET}"
        return f"{C_GRAY}○ OFFLINE{C_RESET}"

# ─────────────────────────────────────────────────────────────
# CLI SCREENS & ACTIONS
# ─────────────────────────────────────────────────────────────

def print_banner():
    print(f"\n{C_BOLD}{C_CYAN}╔══════════════════════════════════════════════════════════╗{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║             BINGO MULTIPLAYER — ADMIN CLI                ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║          Live Cloud Monitor & Real-Time Control          ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}╚══════════════════════════════════════════════════════════╝{C_RESET}")

def show_all_users():
    all_users = sorted(players.keys())
    if not all_users:
        print(f"\n{C_YELLOW}[!] No registered players found.{C_RESET}\n")
        return

    online_count = sum(1 for u in all_users if get_user_status(u)[0] in ("ONLINE", "IN_LOBBY", "PLAYING"))

    print(f"\n{C_BOLD}{C_MAGENTA}── ALL REGISTERED PLAYERS DIRECTORY ({len(all_users)}) ──{C_RESET}")
    header = f"{'Username':<14} {'Display Name':<14} {'Lvl':<5} {'Matches':<8} {'Wins':<6} {'Win%':<7} {'Streak':<7} {'Live Status':<22}"
    print(f"{C_BOLD}{header}{C_RESET}")
    print("─" * 86)

    for u in all_users:
        data = players.get(u, {})
        st, ts, _ = get_user_status(u)
        is_live = st in ("ONLINE", "IN_LOBBY", "PLAYING")
        display = (data.get("displayName") or u)[:13]
        lvl = data.get("level", 1)
        played = data.get("gamesPlayed", 0)
        won = data.get("gamesWon", 0)
        streak = data.get("currentStreak", 0)
        win_rate = f"{(won / played * 100):.0f}%" if played > 0 else "0%"
        status_str = get_status_badge(st, is_live=is_live, ts=ts)

        print(f"@{u:<13} {display:<14} {lvl:<5} {played:<8} {won:<6} {win_rate:<7} {streak:<7} {status_str}")
    print("─" * 86)
    print(f"{C_BOLD}Total Registered:{C_RESET} {len(all_users)} | {C_GREEN}Live Online:{C_RESET} {online_count} | {C_GRAY}Offline:{C_RESET} {len(all_users) - online_count}\n")

def show_online_users():
    online_list = []
    for u in sorted(players.keys()):
        st, ts, diff = get_user_status(u)
        if st in ("ONLINE", "IN_LOBBY", "PLAYING"):
            online_list.append((u, st, ts, diff))

    print(f"\n{C_BOLD}{C_GREEN}── ONLINE PLAYERS ({len(online_list)}) ──{C_RESET}")
    if not online_list:
        print(f"{C_GRAY}No players are currently online (all {len(players)} registered players are offline).{C_RESET}\n")
        return

    header = f"{'Username':<15} {'Display Name':<16} {'Activity':<16} {'Last Heartbeat':<20}"
    print(f"{C_BOLD}{header}{C_RESET}")
    print("─" * 70)

    for u, st, ts, diff in online_list:
        p_data = players.get(u, {})
        disp = p_data.get("displayName") or u
        badge = get_status_badge(st, is_live=True, ts=ts)
        seen = f"Just now ({int(diff)}s ago)" if diff < 60 else format_timestamp(ts)
        print(f"@{u:<14} {disp:<16} {badge:<25} {seen}")
    print("─" * 70)
    print(f"{C_BOLD}Total Live Players:{C_RESET} {len(online_list)}\n")

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
        clear_keyval_key(f"room_{clean_code}")
        print(f"\n{C_GREEN}✓ Room {clean_code} closed and removed!{C_RESET}\n")
    except Exception as e:
        print(f"{C_RED}Failed to close room: {e}{C_RESET}")

def purge_expired_rooms():
    """
    Cleans up stale / ghost rooms from MQTT retained topics and cloud store.
    """
    now_ms = time.time() * 1000
    stale_codes = []
    for code, r in rooms.items():
        st = r.get("status", "UNKNOWN").upper()
        hb = r.get("lastHeartbeat") or r.get("createdAt") or 0
        diff_sec = (now_ms - hb) / 1000.0 if hb > 0 else 999999.0
        if st == "CLOSED" or diff_sec > 300.0:
            stale_codes.append(code)

    if not stale_codes:
        print(f"\n{C_GREEN}No expired rooms to purge. Room list is completely clean!{C_RESET}\n")
        return

    print(f"\n{C_YELLOW}Purging {len(stale_codes)} expired ghost rooms from servers...{C_RESET}")
    try:
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"purger_{int(time.time())}")
        client.connect(MQTT_BROKER, MQTT_PORT, 60)
        for code in stale_codes:
            client.publish(f"bingo/v3/room_meta/{code}", b"", qos=1, retain=True)
            clear_keyval_key(f"room_{code}")
            rooms.pop(code, None)
        time.sleep(0.5)
        client.disconnect()
        print(f"{C_GREEN}✓ Successfully purged {len(stale_codes)} expired rooms!{C_RESET}\n")
    except Exception as e:
        print(f"{C_RED}Purge error: {e}{C_RESET}")

def show_active_rooms(from_menu=False):
    now_ms = time.time() * 1000
    active_rooms = {}
    expired_count = 0

    for code, r in rooms.items():
        st = r.get("status", "UNKNOWN").upper()
        hb = r.get("lastHeartbeat") or r.get("createdAt") or 0
        diff_sec = (now_ms - hb) / 1000.0 if hb > 0 else 999999.0
        
        # Room is active if not CLOSED and heartbeat within 300s (5 mins)
        if st != "CLOSED" and diff_sec < 300.0:
            active_rooms[code] = r
        else:
            expired_count += 1

    print(f"\n{C_BOLD}{C_YELLOW}── LIVE GAME ROOMS ({len(active_rooms)}) ──{C_RESET}")
    if not active_rooms:
        print(f"{C_GRAY}No active game matches in progress right now.{C_RESET}")
        if expired_count > 0:
            print(f"{C_GRAY}({expired_count} expired/finished matches filtered out){C_RESET}\n")
        else:
            print()
        return

    header = f"{'Room Code':<10} {'Host':<16} {'State':<12} {'Joined Players':<28} {'Grid':<6}"
    print(f"{C_BOLD}{header}{C_RESET}")
    print("─" * 75)

    for code, r in active_rooms.items():
        host = f"@{r.get('hostUsername', 'unknown')}"
        state = r.get("status", "WAITING")
        plist = r.get("players", [])
        if isinstance(plist, list):
            names = ", ".join([f"@{p.get('username', p.get('displayName', 'p'))}" for p in plist])
            p_str = f"({len(plist)}) {names}"[:27]
        else:
            p_str = "0 players"
        grid = f"{r.get('boardSize', 5)}x{r.get('boardSize', 5)}"
        print(f"{code:<10} {host:<16} {state:<12} {p_str:<28} {grid:<6}")
    print("─" * 75)
    print(f"{C_BOLD}Active Rooms:{C_RESET} {len(active_rooms)}" + (f" | {C_GRAY}{expired_count} expired{C_RESET}\n" if expired_count > 0 else "\n"))

    if from_menu and sys.stdin.isatty():
        sub = input(f"{C_YELLOW}Options: [D] Delete a Room | [P] Purge Expired Rooms | [Enter] Back: {C_RESET}").strip().lower()
        if sub == "d":
            target_code = input("Enter Room Code to close: ").strip()
            delete_room(target_code)
        elif sub == "p":
            purge_expired_rooms()

def inspect_player(target_username=None, from_menu=False):
    if not target_username:
        raw_user = input(f"\n{C_BOLD}Enter username to inspect (e.g. @bob): {C_RESET}").strip()
        target_username = raw_user.lstrip("@").lower()

    clean = target_username.strip().lower().lstrip("@")
    if not clean:
        return

    p_data = players.get(clean)
    if not p_data:
        print(f"{C_GRAY}Querying Cloud KeyValue store for @{clean}...{C_RESET}")
        p_data = fetch_keyval_user(clean)
        if p_data:
            players[clean] = p_data

    st, ts, diff = get_user_status(clean)
    is_live = st in ("ONLINE", "IN_LOBBY", "PLAYING")

    if not p_data and ts == 0:
        print(f"{C_RED}[!] Player '@{clean}' was not found in registry or cloud database.{C_RESET}")
        return

    display_name = p_data.get("displayName", clean) if p_data else clean
    uid = p_data.get("uid", "None") if p_data else "Unknown"
    level = p_data.get("level", 1) if p_data else 1
    played = p_data.get("gamesPlayed", 0) if p_data else 0
    won = p_data.get("gamesWon", 0) if p_data else 0
    lost = max(0, played - won)
    streak = p_data.get("currentStreak", 0) if p_data else 0
    win_rate = f"{(won / played * 100):.1f}%" if played > 0 else "0.0%"
    last_seen_ts = ts or (p_data.get("lastSeenTimestamp") if p_data else 0)

    print(f"\n{C_BOLD}{C_CYAN}╔═══════════════════════════════════════════════════╗{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║               PLAYER PROFILE CARD                 ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}╚═══════════════════════════════════════════════════╝{C_RESET}")
    print(f"  {C_BOLD}Username:{C_RESET}       @{clean}")
    print(f"  {C_BOLD}Display Name:{C_RESET}   {display_name}")
    print(f"  {C_BOLD}Live Status:{C_RESET}    {get_status_badge(st, is_live=is_live, ts=ts)}")
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
        sub_action = input(f"{C_YELLOW}Options: [M] Match History | [F] Friends List | [K] Kick Player | [Enter] Back: {C_RESET}").strip().lower()
        if sub_action == "m":
            show_and_manage_matches(clean)
        elif sub_action == "f":
            show_and_manage_friends(clean)
        elif sub_action == "k":
            remove_player(clean)

def show_and_manage_matches(target_username=None):
    if not target_username:
        raw_user = input(f"\n{C_BOLD}Enter username to view match history (e.g. @vanabha): {C_RESET}").strip()
        target_username = raw_user.lstrip("@").lower()

    clean = target_username.strip().lower().lstrip("@")
    if not clean:
        return

    print(f"\n{C_CYAN}Loading match records from cloud storage for @{clean}...{C_RESET}")
    bin_id, backup = fetch_player_cloud_backup(clean)

    if not backup or "matchHistory" not in backup:
        print(f"{C_YELLOW}[!] No cloud match records found for @{clean}.{C_RESET}\n")
        return

    history = backup.get("matchHistory", [])
    if not history:
        print(f"{C_YELLOW}[!] Player @{clean} currently has 0 recorded matches in history.{C_RESET}\n")
        return

    print(f"\n{C_BOLD}{C_CYAN}╔══════════════════════════════════════════════════════════════════════════════════════╗{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║                    MATCH HISTORY FOR @{clean.upper():<14} ({len(history)} matches)                     ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}╚══════════════════════════════════════════════════════════════════════════════════════╝{C_RESET}")

    header = f"{'#':<4} {'Date & Time':<17} {'Mode':<18} {'Opponent':<18} {'Result':<10} {'Grid':<6} {'Match ID':<10}"
    print(f"{C_BOLD}{header}{C_RESET}")
    print("─" * 86)

    for idx, m in enumerate(history, 1):
        ts = m.get("timestamp", 0)
        dt_str = format_timestamp(ts)
        mode = m.get("mode", "Online Match")[:17]
        opp = m.get("opponentName", "Opponent")[:17]
        is_draw = m.get("isDraw", False)
        did_win = m.get("didWin", False)
        if is_draw:
            res_str = f"{C_YELLOW}DRAW 🤝{C_RESET}"
        elif did_win:
            res_str = f"{C_GREEN}WIN 👑{C_RESET}"
        else:
            res_str = f"{C_RED}LOSS ❌{C_RESET}"
        grid = f"{m.get('boardSize', 5)}x{m.get('boardSize', 5)}"
        mid = m.get("id", "N/A")[:10]
        print(f"{idx:<4} {dt_str:<17} {mode:<18} {opp:<18} {res_str:<19} {grid:<6} {mid:<10}")

    print("─" * 86)

    if sys.stdin.isatty():
        sub = input(f"\n{C_YELLOW}Options: [D] Delete a specific match | [C] Clear ALL match history | [Enter] Back: {C_RESET}").strip().lower()
        if sub == "d":
            target = input("Enter Match # (1, 2, ...) or Match ID to delete: ").strip()
            if not target:
                return
            del_idx = None
            if target.isdigit():
                num = int(target)
                if 1 <= num <= len(history):
                    del_idx = num - 1
            if del_idx is None:
                for i, m in enumerate(history):
                    if m.get("id") == target:
                        del_idx = i
                        break

            if del_idx is None:
                print(f"{C_RED}[!] Match '{target}' not found.{C_RESET}\n")
                return

            removed = history.pop(del_idx)
            backup["matchHistory"] = history
            if save_player_cloud_backup(bin_id, backup):
                print(f"{C_GREEN}✓ Successfully deleted match {removed.get('id', '')} (vs {removed.get('opponentName', '')})!{C_RESET}\n")
            else:
                print(f"{C_RED}[!] Failed to update cloud storage.{C_RESET}\n")

        elif sub == "c":
            confirm = input(f"{C_RED}Are you SURE you want to clear ALL {len(history)} match records for @{clean}? (yes/no): {C_RESET}").strip().lower()
            if confirm == "yes":
                backup["matchHistory"] = []
                if save_player_cloud_backup(bin_id, backup):
                    print(f"{C_GREEN}✓ All match history for @{clean} cleared successfully!{C_RESET}\n")
                else:
                    print(f"{C_RED}[!] Failed to update cloud storage.{C_RESET}\n")

def show_and_manage_friends(target_username=None):
    if not target_username:
        raw_user = input(f"\n{C_BOLD}Enter username to view friends list (e.g. @vanabha): {C_RESET}").strip()
        target_username = raw_user.lstrip("@").lower()

    clean = target_username.strip().lower().lstrip("@")
    if not clean:
        return

    print(f"\n{C_CYAN}Loading friends list from cloud storage for @{clean}...{C_RESET}")
    friends = fetch_player_friends_details(clean)

    if not friends:
        print(f"{C_YELLOW}[!] Player @{clean} currently has 0 friends in their list.{C_RESET}\n")
        return

    print(f"\n{C_BOLD}{C_CYAN}╔══════════════════════════════════════════════════════════════════════════╗{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}║                    FRIENDS LIST FOR @{clean.upper():<14} ({len(friends)} friends)                  ║{C_RESET}")
    print(f"{C_BOLD}{C_CYAN}╚══════════════════════════════════════════════════════════════════════════╝{C_RESET}")

    header = f"{'#':<4} {'Username':<16} {'Display Name':<18} {'Live Status':<15} {'Last Seen':<17}"
    print(f"{C_BOLD}{header}{C_RESET}")
    print("─" * 74)

    for idx, f in enumerate(friends, 1):
        fu = f.get("username", "unknown")
        fdn = f.get("displayName", fu)[:17]
        st, ts, diff = get_user_status(fu)
        is_live = st in ("ONLINE", "IN_LOBBY", "PLAYING")
        badge = get_status_badge(st, is_live=is_live, ts=ts)
        last_seen = format_timestamp(ts or f.get("lastSeenTimestamp", 0))
        print(f"{idx:<4} @{fu:<15} {fdn:<18} {badge:<24} {last_seen:<17}")

    print("─" * 74)

    if sys.stdin.isatty():
        sub = input(f"\n{C_YELLOW}Options: [R] Remove a friend | [C] Clear ALL friends | [Enter] Back: {C_RESET}").strip().lower()
        if sub == "r":
            target = input("Enter friend username to remove (e.g. @player): ").strip().lstrip("@").lower()
            if not target:
                return
            new_list = [f for f in friends if f.get("username", "").strip().lower() != target]
            if len(new_list) == len(friends):
                print(f"{C_RED}[!] Friend '@{target}' not found in @{clean}'s friends list.{C_RESET}\n")
                return

            if save_player_friends_details(clean, new_list):
                print(f"{C_GREEN}✓ Successfully removed @{target} from @{clean}'s friends list!{C_RESET}\n")
            else:
                print(f"{C_RED}[!] Failed to update cloud database.{C_RESET}\n")

        elif sub == "c":
            confirm = input(f"{C_RED}Are you SURE you want to clear ALL friends for @{clean}? (yes/no): {C_RESET}").strip().lower()
            if confirm == "yes":
                if save_player_friends_details(clean, []):
                    print(f"{C_GREEN}✓ All friends for @{clean} have been cleared!{C_RESET}\n")
                else:
                    print(f"{C_RED}[!] Failed to update cloud database.{C_RESET}\n")


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
        clear_keyval_key(f"friends_{clean}")
        
        # Remove from directory
        all_dir = fetch_keyval_directory()
        if clean in all_dir:
            all_dir = [u for u in all_dir if u != clean]
            update_keyval_directory(all_dir)
            
        print(f"  {C_GREEN}✓ Purged Cloud KeyValue database entries & directory.{C_RESET}")
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
            print("  bingo                       Launch interactive admin menu")
            print("  bingo -u, --users           Show all players directory")
            print("  bingo -o, --online          Show online players")
            print("  bingo -r, --rooms           Show active game rooms")
            print("  bingo -i, --inspect <user>  Inspect a specific player")
            print("  bingo -m, --matches <user>  View & manage player match history")
            print("  bingo -f, --friends <user>  View & manage player friends list")
            print("  bingo -k, --kick <user>     Kick & delete a player")
            print("  bingo -p, --purge           Purge expired ghost rooms\n")
            return

        print_banner()
        fetch_live_data(timeout=2.5)

        if arg in ("-u", "--users"):
            show_all_users()
        elif arg in ("-o", "--online"):
            show_online_users()
        elif arg in ("-r", "--rooms"):
            show_active_rooms()
        elif arg in ("-i", "--inspect"):
            target = sys.argv[2] if len(sys.argv) > 2 else ""
            inspect_player(target)
        elif arg in ("-m", "--matches"):
            target = sys.argv[2] if len(sys.argv) > 2 else ""
            show_and_manage_matches(target)
        elif arg in ("-f", "--friends"):
            target = sys.argv[2] if len(sys.argv) > 2 else ""
            show_and_manage_friends(target)
        elif arg in ("-k", "--kick"):
            target = sys.argv[2] if len(sys.argv) > 2 else ""
            remove_player(target)
        elif arg in ("-p", "--purge"):
            purge_expired_rooms()
        else:
            print(f"{C_RED}Unknown option '{arg}'. Run 'bingo --help' for options.{C_RESET}\n")
        return

    print_banner()
    print(f"{C_CYAN}Connecting to Bingo game server to load live data...{C_RESET}")
    fetch_live_data(timeout=2.5)
    print(f"{C_GREEN}Data synchronized ({len(players)} players, {len(rooms)} rooms).{C_RESET}")

    while True:
        print(f"\n{C_BOLD}────────────── MAIN MENU ──────────────{C_RESET}")
        print(f"  {C_BOLD}[1]{C_RESET} 📋 All Players Directory")
        print(f"  {C_BOLD}[2]{C_RESET} 🟢 Online Players Only")
        print(f"  {C_BOLD}[3]{C_RESET} 🎮 Active Game Matches / Rooms")
        print(f"  {C_BOLD}[4]{C_RESET} 🔍 Inspect Player Details & Stats")
        print(f"  {C_BOLD}[5]{C_RESET} 📜 View & Manage Match History")
        print(f"  {C_BOLD}[6]{C_RESET} 👥 View & Manage Friends List")
        print(f"  {C_BOLD}[7]{C_RESET} 🚫 Kick & Delete Player from Database")
        print(f"  {C_BOLD}[8]{C_RESET} 🧹 Purge Expired Ghost Rooms")
        print(f"  {C_BOLD}[9]{C_RESET} 🔄 Refresh Server Data")
        print(f"  {C_BOLD}[0]{C_RESET} 🚪 Exit")
        print("───────────────────────────────────────")

        choice = input(f"{C_BOLD}Select an option (0-9): {C_RESET}").strip()

        if choice == "1":
            show_all_users()
        elif choice == "2":
            show_online_users()
        elif choice == "3":
            show_active_rooms(from_menu=True)
        elif choice == "4":
            inspect_player(from_menu=True)
        elif choice == "5":
            show_and_manage_matches()
        elif choice == "6":
            show_and_manage_friends()
        elif choice == "7":
            remove_player()
        elif choice == "8":
            purge_expired_rooms()
        elif choice == "9":
            print(f"\n{C_CYAN}Refreshing live data from servers...{C_RESET}")
            fetch_live_data(timeout=2.5)
            print(f"{C_GREEN}Refreshed! ({len(players)} players, {len(rooms)} rooms){C_RESET}")
        elif choice in ("0", "exit", "quit", "q"):
            print(f"\n{C_CYAN}Goodbye! 👋{C_RESET}\n")
            break
        else:
            print(f"{C_RED}[!] Invalid choice. Please choose 0 to 9.{C_RESET}")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print(f"\n\n{C_YELLOW}Admin CLI exited.{C_RESET}\n")
