#!/usr/bin/env python3
"""
Virtual Bingo Bots - 5-Player Headless MQTT Runner
Simulates 5 active players (Kevin, Stuart, Dave, Phil, Bob) joining a Bingo room,
handling lobby readiness, automated turn picking, and interactive disconnect/reconnect simulation.
"""

import sys
import time
import json
import random
import threading
import ssl
from typing import Dict, List, Optional, Set
import paho.mqtt.client as mqtt

# --- Configuration ---
BROKER_HOST = "broker.emqx.io"
BROKER_PORT = 8883
BROKER_USER = ""
BROKER_PASS = ""

# 5 Virtual Bots (Kevin is Host on emulator)
BOTS_CONFIG = [
    {"id": "bot_otto_1", "username": "otto_bot", "name": "Otto 🤖"},
    {"id": "bot_stuart_2", "username": "stuart_bot", "name": "Stuart 🤖"},
    {"id": "bot_dave_3", "username": "dave_bot", "name": "Dave 🤖"},
    {"id": "bot_phil_4", "username": "phil_bot", "name": "Phil 🤖"},
    {"id": "bot_bob_5", "username": "bob_bot", "name": "Bob 🤖"},
]

# --- Deterministic Java Random & Shuffler (Exact replica of Java/Kotlin java.util.Random) ---
class JavaRandom:
    def __init__(self, seed: int):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next(self, bits: int) -> int:
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.seed >> (48 - bits)

    def next_int(self, n: int) -> int:
        if (n & -n) == n:
            return (n * self.next(31)) >> 31
        bits = self.next(31)
        val = bits % n
        while (bits - val + (n - 1)) < 0:
            bits = self.next(31)
            val = bits % n
        return val

def java_shuffle(lst: list, seed: int) -> list:
    rng = JavaRandom(seed)
    shuffled = list(lst)
    for i in range(len(shuffled), 1, -1):
        j = rng.next_int(i)
        shuffled[i - 1], shuffled[j] = shuffled[j], shuffled[i - 1]
    return shuffled

# --- Bot State & Session Manager ---
class BotRunner:
    def __init__(self, room_code: str):
        self.room_code = room_code.strip().upper()
        self.topic = f"bingo/v3/room/{self.room_code}"
        self.running = True
        self.client = None
        self.lock = threading.Lock()

        # Bot status: id -> {"connected": bool, "config": dict, "board": list}
        self.bots: Dict[str, dict] = {}
        for b in BOTS_CONFIG:
            self.bots[b["id"]] = {
                "config": b,
                "connected": True,
                "dropped": False,
                "ready": True
            }

        # Match State
        self.in_game = False
        self.seed = 0
        self.board_size = 5
        self.turn_number = 1
        self.current_turn_player_id = ""
        self.all_participants: List[str] = [] # Ordered IDs
        self.turn_order: List[str] = []       # Shuffled IDs
        self.picked_numbers: List[int] = []
        self.picked_by: List[str] = []
        self.eliminated_players: Set[str] = set()
        self.auto_pick = True
        self.pick_delay = 2.0 # seconds before a bot picks
        self.scheduled_pick_thread = None

    def find_bot(self, query: str) -> Optional[dict]:
        q = query.strip().lower()
        # By index (1-5)
        if q.isdigit():
            idx = int(q) - 1
            if 0 <= idx < len(BOTS_CONFIG):
                return self.bots[BOTS_CONFIG[idx]["id"]]
        # By name or id
        for b_id, b_data in self.bots.items():
            name = b_data["config"]["name"].lower()
            uname = b_data["config"]["username"].lower()
            if q in b_id.lower() or q in name or q in uname:
                return b_data
        return None

    def start(self):
        # Setup MQTT Client
        client_id = f"bingo_runner_{random.randint(10000, 99999)}"
        if hasattr(mqtt, "CallbackAPIVersion"):
            self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id)
        else:
            self.client = mqtt.Client(client_id=client_id)

        self.client.username_pw_set(BROKER_USER, BROKER_PASS)
        self.client.tls_set(cert_reqs=ssl.CERT_NONE)
        self.client.tls_insecure_set(True)

        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.client.on_disconnect = self.on_disconnect

        print(f"\n[Network] Connecting to EMQX broker ({BROKER_HOST}:{BROKER_PORT})...")
        self.client.connect(BROKER_HOST, BROKER_PORT, 60)
        self.client.loop_start()

        # Start background heartbeat thread
        self.heartbeat_thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        self.heartbeat_thread.start()

    def stop(self):
        self.running = False
        if self.client:
            # Send LEAVE for all bots
            for b_id, b_data in self.bots.items():
                if not b_data["dropped"]:
                    leave_pkt = {
                        "type": "LEAVE",
                        "playerId": b_id,
                        "displayName": b_data["config"]["name"],
                        "timestamp": int(time.time() * 1000)
                    }
                    self.publish(json.dumps(leave_pkt))
            self.client.loop_stop()
            self.client.disconnect()
            print("[Network] Disconnected cleanly.")

    def publish(self, payload: str):
        if self.client and self.running:
            self.client.publish(self.topic, payload, qos=1)

    def on_connect(self, client, userdata, flags, rc, properties=None):
        print(f"[Network] Connected to broker successfully! Room: {self.room_code}")
        print(f"[Network] Subscribing to topic: {self.topic}")
        client.subscribe(self.topic, qos=1)

        # Broadcast JOIN & READY for all bots
        time.sleep(0.5)
        now_ms = int(time.time() * 1000)
        for b_id, b_data in self.bots.items():
            cfg = b_data["config"]
            join_pkt = {
                "type": "JOIN",
                "playerId": b_id,
                "displayName": cfg["name"],
                "username": cfg["username"],
                "isHost": False,
                "readyStatus": "READY",
                "readyVersion": 1,
                "timestamp": now_ms
            }
            self.publish(json.dumps(join_pkt))
            # Also send micro-payload ready
            self.publish(f"R|{b_id}|READY|{cfg['username']}|{cfg['name']}|1|bot_instance")
            print(f"  ➜ {cfg['name']} joined and set status to READY")

    def on_disconnect(self, client, userdata, *args, **kwargs):
        if self.running:
            print(f"[Network] Disconnected from broker. Attempting reconnect...")

    def on_message(self, client, userdata, msg):
        try:
            payload = msg.payload.decode("utf-8").strip()
            self._handle_packet(payload)
        except Exception as e:
            print(f"[Error] Failed to process message: {e}")

    def _handle_packet(self, payload: str):
        with self.lock:
            # 1. Micro-payload parsing
            if payload.startswith("P|"):
                self._handle_pick_micro(payload)
            elif payload.startswith("S|"):
                self._handle_start_micro(payload)
            elif payload.startswith("T|"):
                # T|playerId|turnNumber|currentTurnId|history|seed|senderInstanceId|pickedBy
                parts = payload.split("|")
                turn_num = int(parts[2]) if len(parts) > 2 and parts[2].isdigit() else self.turn_number
                next_turn = parts[3] if len(parts) > 3 else ""
                print(f"\n⌛ [TURN TIMEOUT / SKIP] Previous player timed out. Next turn: {self._player_label(next_turn)}")
                self.turn_number = max(self.turn_number, turn_num)
                if next_turn:
                    self.current_turn_player_id = next_turn
                    self._check_and_trigger_bot_turn()
            elif payload.startswith("HH|"):
                parts = payload.split("|")
                print(f"\n⏸️  [HOST HOLD] Host {parts[1]} is on hold (app backgrounded/offline). Rem turn: {parts[2]}ms")
            elif payload.startswith("HR|"):
                parts = payload.split("|")
                print(f"\n▶️  [HOST RESUMED] Host {parts[1]} has returned! Auto-resuming match.")
            elif payload.startswith("PEX|"):
                parts = payload.split("|")
                print(f"\n⏳ [PAUSE EXTEND] Player {parts[1]} requested pause extension (+30s).")
            elif payload.startswith("ELIM|"):
                parts = payload.split("|")
                target = parts[1]
                self.eliminated_players.add(target)
                print(f"\n🚫 [PLAYER ELIMINATED] Player {target} was kicked/eliminated by referee.")
            elif payload.startswith("{"):
                # JSON fallback
                try:
                    data = json.loads(payload)
                    p_type = data.get("type", "")
                    if p_type == "START_GAME":
                        self._handle_start_json(data)
                    elif p_type == "PICK_NUMBER":
                        self._handle_pick_json(data)
                    elif p_type == "TURN_TIMEOUT":
                        t_num = data.get("turnNumber", self.turn_number)
                        next_turn = data.get("currentTurnPlayerId", "")
                        print(f"\n⌛ [TURN TIMEOUT / SKIP JSON] Next turn: {self._player_label(next_turn)}")
                        self.turn_number = max(self.turn_number, t_num)
                        if next_turn:
                            self.current_turn_player_id = next_turn
                            self._check_and_trigger_bot_turn()
                    elif p_type == "GAME_PAUSED":
                        print(f"\n⏸️  [GAME PAUSED] Paused by {data.get('pauseOwnerId', 'Host')}. Reason: {data.get('pauseReason')}")
                    elif p_type == "GAME_RESUMED":
                        print(f"\n▶️  [GAME RESUMED] Game resumed by {data.get('playerId', 'Host')}")
                    elif p_type == "BINGO_CLAIMED":
                        print(f"\n🎉 [BINGO CLAIMED] Winner: {data.get('winnerPlayerId')} ({data.get('displayName')})!")
                    elif p_type == "ROOM_STATE":
                        pass
                except Exception:
                    pass

    def _handle_start_micro(self, payload: str):
        # S|playerId|boardSize|seed|isManualBoard
        parts = payload.split("|")
        seed = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else 12345
        self._start_match(seed, 5, parts[1], [])

    def _handle_start_json(self, data: dict):
        seed = data.get("seed", 0)
        size = data.get("boardSize", 5)
        first_turn = data.get("currentTurnPlayerId", "")
        players = data.get("players", [])
        self._start_match(seed, size, first_turn, players)

    def _start_match(self, seed: int, size: int, first_turn: str, players: list):
        self.in_game = True
        self.seed = seed
        self.board_size = size
        self.turn_number = 1
        self.picked_numbers.clear()
        self.picked_by.clear()
        self.eliminated_players.clear()

        # Extract participants
        player_ids = [p.get("id") for p in players if p.get("id")]
        if not player_ids:
            # Fallback: all bot IDs
            player_ids = [b["id"] for b in BOTS_CONFIG]
        self.all_participants = sorted(list(set(player_ids)))

        # Deterministic turn order (Exact Java Collections.shuffle)
        self.turn_order = java_shuffle(self.all_participants, seed)
        self.current_turn_player_id = first_turn if first_turn else (self.turn_order[0] if self.turn_order else "")

        print("\n" + "="*60)
        print("🎮 🎮 🎮  MATCH STARTED!  🎮 🎮 🎮")
        print(f"Room Code:       {self.room_code}")
        print(f"Match Seed:      {self.seed}")
        print(f"Board Size:      {self.board_size}x{self.board_size}")
        print(f"Turn Order:      {' ➔ '.join(self.turn_order)}")
        print(f"First Turn:      {self._player_label(self.current_turn_player_id)}")
        print("="*60 + "\n")

        self._check_and_trigger_bot_turn()

    def _handle_pick_micro(self, payload: str):
        # P|number|playerId|turnNumber|currentTurnId|history|seed|senderInstanceId
        parts = payload.split("|")
        num = int(parts[1]) if len(parts) > 1 and parts[1].lstrip('-').isdigit() else 0
        picker = parts[2] if len(parts) > 2 else ""
        turn_num = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else self.turn_number
        next_turn = parts[4] if len(parts) > 4 else ""
        self._on_number_picked(num, picker, turn_num, next_turn)

    def _handle_pick_json(self, data: dict):
        num = data.get("number", 0)
        picker = data.get("playerId", "")
        turn_num = data.get("turnNumber", self.turn_number)
        next_turn = data.get("currentTurnPlayerId", "")
        self._on_number_picked(num, picker, turn_num, next_turn)

    def _on_number_picked(self, num: int, picker: str, turn_num: int, next_turn: str):
        if num > 0 and num not in self.picked_numbers:
            self.picked_numbers.append(num)
            self.picked_by.append(picker)

        self.turn_number = max(self.turn_number, turn_num + 1)
        if next_turn:
            self.current_turn_player_id = next_turn
        else:
            self.current_turn_player_id = self._calculate_next_turn(picker)

        print(f"🎲 [Turn {turn_num}] {self._player_label(picker)} picked #{num}! ➔ Next turn: {self._player_label(self.current_turn_player_id)}")
        self._check_and_trigger_bot_turn()

    def _calculate_next_turn(self, current_picker: str) -> str:
        active = [p for p in self.turn_order if p not in self.eliminated_players]
        if not active:
            return ""
        if current_picker in active:
            idx = active.index(current_picker)
            return active[(idx + 1) % len(active)]
        return active[0]

    def _check_and_trigger_bot_turn(self):
        curr_id = self.current_turn_player_id
        if curr_id in self.bots:
            bot = self.bots[curr_id]
            if bot["dropped"]:
                print(f"⏳ [{bot['config']['name']}] It's my turn, but I am DROPPED/OFFLINE. Silence will trigger Host referee!")
                return
            if self.auto_pick:
                # Schedule pick in self.pick_delay seconds
                if self.scheduled_pick_thread and self.scheduled_pick_thread.is_alive():
                    return
                self.scheduled_pick_thread = threading.Thread(target=self._execute_bot_pick_delayed, args=(curr_id,), daemon=True)
                self.scheduled_pick_thread.start()

    def _execute_bot_pick_delayed(self, bot_id: str):
        time.sleep(self.pick_delay)
        with self.lock:
            # Check if still this bot's turn and not dropped
            if self.current_turn_player_id != bot_id:
                return
            bot = self.bots.get(bot_id)
            if not bot or bot["dropped"]:
                return

            # Choose an unpicked number from 1..25
            max_num = self.board_size * self.board_size
            available = [n for n in range(1, max_num + 1) if n not in self.picked_numbers]
            if not available:
                print("⚠️  No more numbers available to pick!")
                return

            chosen = random.choice(available)
            next_turn_id = self._calculate_next_turn(bot_id)
            turn_num = self.turn_number

            self.picked_numbers.append(chosen)
            self.picked_by.append(bot_id)
            self.turn_number += 1
            self.current_turn_player_id = next_turn_id

            # Publish PICK_NUMBER micro-payload
            hist_str = ",".join(str(n) for n in self.picked_numbers)
            by_str = ",".join(self.picked_by)
            packet_str = f"P|{chosen}|{bot_id}|{turn_num}|{next_turn_id}|{hist_str}|{self.seed}|bot_instance|{by_str}|0|{self.board_size}"
            self.publish(packet_str)

            print(f"🤖 [{bot['config']['name']}] Picked #{chosen}! ➔ Next: {self._player_label(next_turn_id)}")
            self._check_and_trigger_bot_turn()

    def _heartbeat_loop(self):
        while self.running:
            time.sleep(2.0)
            with self.lock:
                now_ms = int(time.time() * 1000)
                status_str = "IN_GAME" if self.in_game else "READY"
                for b_id, b_data in self.bots.items():
                    if not b_data["dropped"]:
                        cfg = b_data["config"]
                        # H|playerId|displayName|isHost|timestamp|username||status|version
                        hb_str = f"H|{b_id}|{cfg['name']}|0|{now_ms}|{cfg['username']}||{status_str}|1"
                        self.publish(hb_str)

    def _player_label(self, player_id: str) -> str:
        if not player_id:
            return "Unknown"
        if player_id in self.bots:
            return self.bots[player_id]["config"]["name"]
        return f"Host/Player ({player_id[:8]})"

    # --- Interactive Commands ---
    def drop_bot(self, query: str):
        with self.lock:
            bot = self.find_bot(query)
            if not bot:
                print(f"❌ Bot '{query}' not found. Use 1-5 or bot name (kevin, stuart, dave, phil, bob).")
                return
            bot["dropped"] = True
            name = bot["config"]["name"]
            print(f"\n🔻 [{name}] DROPPED! Stopped sending heartbeats. (Simulating app killed / backgrounded)")
            print(f"   ➜ Watch the Host screen: 10s silent buffer will begin, followed by 50s countdown or decision popup.")

    def reconnect_bot(self, query: str):
        with self.lock:
            bot = self.find_bot(query)
            if not bot:
                print(f"❌ Bot '{query}' not found. Use 1-5 or bot name.")
                return
            bot["dropped"] = False
            name = bot["config"]["name"]
            b_id = bot["config"]["id"]
            cfg = bot["config"]
            now_ms = int(time.time() * 1000)
            status_str = "IN_GAME" if self.in_game else "READY"
            # Immediate heartbeat burst
            self.publish(f"H|{b_id}|{cfg['name']}|0|{now_ms}|{cfg['username']}||{status_str}|1")
            print(f"\n🔺 [{name}] RECONNECTED! Heartbeats resumed.")
            print(f"   ➜ Watch the Host screen: The referee should detect reconnect and trigger 3s countdown auto-resume!")
            self._check_and_trigger_bot_turn()

    def print_status(self):
        with self.lock:
            print("\n" + "="*60)
            print(f" ROOM STATUS: {self.room_code} | Game State: {'IN-GAME' if self.in_game else 'LOBBY'}")
            print(f" Turn: #{self.turn_number} | Current Picker: {self._player_label(self.current_turn_player_id)}")
            print(f" Numbers Picked ({len(self.picked_numbers)}/25): {self.picked_numbers}")
            print("-" * 60)
            print(" # | Bot Name | Status      | In Turn | Dropped?")
            print("-" * 60)
            for idx, b_cfg in enumerate(BOTS_CONFIG):
                b_data = self.bots[b_cfg["id"]]
                is_turn = " ⭐ TURN" if b_cfg["id"] == self.current_turn_player_id else "        "
                drop_status = "❌ YES (OFFLINE)" if b_data["dropped"] else "✅ Active (Online)"
                print(f" {idx + 1} | {b_cfg['name']:<8} | {('IN_GAME' if self.in_game else 'READY'):<11} |{is_turn}| {drop_status}")
            print("="*60 + "\n")


def print_help():
    print("""
Available Interactive Commands:
  status               - Print current room, turn, and bot statuses
  drop <1-5 or name>   - Drop a bot (stop heartbeats to simulate unhandled disconnect)
  reconnect <1-5>      - Reconnect a dropped bot (resumes heartbeats to trigger auto-resume)
  drop_all             - Drop all 5 bots simultaneously
  reconnect_all        - Reconnect all 5 bots
  auto <on/off>        - Toggle automated number picking on bot turns
  delay <seconds>      - Set bot pick delay (default 2.0s)
  help                 - Show this help menu
  quit / exit          - Disconnect and exit
""")

def main():
    room_code = sys.argv[1] if len(sys.argv) > 1 else input("Enter Bingo Room Code: ").strip()
    if not room_code:
        print("Error: Room code is required.")
        sys.exit(1)

    runner = BotRunner(room_code)
    runner.start()
    time.sleep(1.0)
    print_help()

    try:
        while runner.running:
            cmd = input(f"bingo-bot[{room_code}]> ").strip()
            if not cmd:
                continue
            parts = cmd.split()
            action = parts[0].lower()

            if action in ("status", "s"):
                runner.print_status()
            elif action == "drop":
                if len(parts) > 1:
                    runner.drop_bot(parts[1])
                else:
                    print("Usage: drop <1-5 or name> (e.g., 'drop 1' or 'drop kevin')")
            elif action in ("reconnect", "rec"):
                if len(parts) > 1:
                    runner.reconnect_bot(parts[1])
                else:
                    print("Usage: reconnect <1-5 or name>")
            elif action == "drop_all":
                for i in range(1, 6):
                    runner.drop_bot(str(i))
            elif action == "reconnect_all":
                for i in range(1, 6):
                    runner.reconnect_bot(str(i))
            elif action == "auto":
                if len(parts) > 1 and parts[1].lower() in ("off", "0", "false"):
                    runner.auto_pick = False
                    print("Auto-pick DISABLED. Bots will wait for manual pick.")
                else:
                    runner.auto_pick = True
                    print("Auto-pick ENABLED.")
            elif action == "delay":
                if len(parts) > 1 and parts[1].replace('.', '', 1).isdigit():
                    runner.pick_delay = float(parts[1])
                    print(f"Pick delay set to {runner.pick_delay}s")
            elif action in ("quit", "exit", "q"):
                break
            elif action == "help":
                print_help()
            else:
                print(f"Unknown command: '{action}'. Type 'help' for available commands.")
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        runner.stop()

if __name__ == "__main__":
    main()
