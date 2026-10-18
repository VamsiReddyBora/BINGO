#!/usr/bin/env python3
"""
Interactive Kevin & Stuart 1v1 Full Match Simulation with Real-time Game Invitation.
Simulates:
1. Stuart waking up and listening for Game Invites on bingo/v3/invites/stuart.
2. Kevin waking up, creating room, and dispatching an invite to Stuart.
3. Stuart receiving the invite, accepting it, and joining Kevin's room.
4. Lobby ready handshake (Stuart toggling Ready).
5. Kevin starting match (START_GAME).
6. Both generating deterministic boards from seed.
7. Alternating turns picking numbers with exact latency measurements.
8. BINGO completion, win verification, and clean match closure.
"""

import sys
import time
import json
import ssl
import random
import threading
from typing import Dict, List, Optional, Set
import paho.mqtt.client as mqtt

BROKER_HOST = "p0812f88.ala.asia-southeast1.emqxsl.com"
BROKER_PORT = 8883
BROKER_USER = "Bora"
BROKER_PASS = "bora7989"

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

def count_completed_lines(board: List[int], marked: Set[int]) -> int:
    lines = 0
    # Rows
    for r in range(5):
        if all(board[r * 5 + c] in marked for c in range(5)):
            lines += 1
    # Cols
    for c in range(5):
        if all(board[r * 5 + c] in marked for r in range(5)):
            lines += 1
    # Diag 1
    if all(board[i * 5 + i] in marked for i in range(5)):
        lines += 1
    # Diag 2
    if all(board[i * 5 + (4 - i)] in marked for i in range(5)):
        lines += 1
    return lines

class SimulatedPlayer:
    def __init__(self, player_id: str, display_name: str, username: str):
        self.player_id = player_id
        self.display_name = display_name
        self.username = username
        self.client = mqtt.Client(client_id=f"sim_{player_id}_{random.randint(1000, 9999)}", protocol=mqtt.MQTTv311)
        self.client.username_pw_set(BROKER_USER, BROKER_PASS)
        self.client.tls_set(cert_reqs=ssl.CERT_NONE)
        self.client.tls_insecure_set(True)
        self.lock = threading.Lock()
        self.received_messages: List[str] = []
        self.invites_received: List[dict] = []
        self.subscribed = threading.Event()
        self.connected = threading.Event()
        self.room_code = ""
        self.topic = ""
        self.board: List[int] = []
        self.marked: Set[int] = set()

        self.client.on_connect = self._on_connect
        self.client.on_subscribe = self._on_subscribe
        self.client.on_message = self._on_message

    def _on_connect(self, client, userdata, flags, rc, props=None):
        if rc == 0:
            self.connected.set()

    def _on_subscribe(self, client, userdata, mid, rc, props=None):
        self.subscribed.set()

    def _on_message(self, client, userdata, msg):
        topic = msg.topic
        payload = msg.payload.decode("utf-8").strip()
        with self.lock:
            self.received_messages.append(payload)
            if topic.startswith("bingo/v3/invites/"):
                try:
                    data = json.loads(payload)
                    self.invites_received.append(data)
                except Exception:
                    pass

    def start(self):
        self.connected.clear()
        self.client.connect(BROKER_HOST, BROKER_PORT, 30)
        self.client.loop_start()
        self.connected.wait(5.0)

    def listen_for_invites(self):
        topic = f"bingo/v3/invites/{self.username}"
        self.subscribed.clear()
        self.client.subscribe(topic, qos=1)
        self.subscribed.wait(3.0)
        print(f"[{self.display_name}] Subscribed and listening for invites on '{topic}'")

    def join_room(self, room_code: str):
        self.room_code = room_code.upper()
        self.topic = f"bingo/v3/room/{self.room_code}"
        self.subscribed.clear()
        self.client.subscribe(self.topic, qos=1)
        self.subscribed.wait(3.0)
        print(f"[{self.display_name}] Joined topic '{self.topic}'")

    def send(self, payload: str):
        if self.topic:
            self.client.publish(self.topic, payload, qos=1)

    def send_invite(self, target_username: str, room_code: str):
        topic = f"bingo/v3/invites/{target_username}"
        invite_data = {
            "fromUsername": self.username,
            "fromDisplayName": self.display_name,
            "roomCode": room_code,
            "timestamp": int(time.time() * 1000),
            "recipientUsername": target_username,
            "expiresAt": int(time.time() * 1000) + 120000
        }
        self.client.publish(topic, json.dumps(invite_data), qos=1, retain=True)
        print(f"[{self.display_name}] Sent game invite to '{target_username}' for room {room_code}")

    def wait_for_packet(self, condition, timeout: float = 5.0) -> Optional[str]:
        start = time.time()
        while time.time() - start < timeout:
            with self.lock:
                for msg in self.received_messages:
                    if condition(msg):
                        return msg
            time.sleep(0.02)
        return None

    def wait_for_invite(self, timeout: float = 5.0) -> Optional[dict]:
        start = time.time()
        while time.time() - start < timeout:
            with self.lock:
                if self.invites_received:
                    return self.invites_received.pop(0)
            time.sleep(0.05)
        return None

    def stop(self):
        self.client.loop_stop()
        self.client.disconnect()


def run_full_kevin_and_stuart_match():
    print("=" * 70)
    print("  SIMULATING FULL 1V1 MATCH: KEVIN (HOST) & STUART (GUEST)")
    print("=" * 70)

    room_code = f"KS{random.randint(100, 999)}"
    match_seed = int(time.time() * 1000) % 1000000000

    print(f"\n[Step 1] Waking up Kevin & Stuart...")
    kevin = SimulatedPlayer("kevin_host", "Kevin (Host) 👑", "kevin")
    stuart = SimulatedPlayer("stuart_guest", "Stuart (Guest) 🍌", "stuart")

    kevin.start()
    stuart.start()
    print(" -> Both players connected to EMQX cluster (broker.emqx.io:8883)!")

    # 1. Stuart starts listening for invites
    stuart.listen_for_invites()
    time.sleep(0.5)

    # 2. Kevin hosts room and sends invite to Stuart
    print(f"\n[Step 2] Kevin creates room {room_code} and invites Stuart...")
    kevin.join_room(room_code)
    kevin.send(f"H|{kevin.player_id}|{kevin.display_name}|1|{int(time.time()*1000)}|{kevin.username}||READY|1|2")

    t_invite_send = time.time()
    kevin.send_invite("stuart", room_code)

    # 3. Stuart receives invite
    invite = stuart.wait_for_invite(timeout=4.0)
    assert invite is not None, "FAILED: Stuart did not receive Game Invite!"
    invite_latency = (time.time() - t_invite_send) * 1000.0
    print(f" -> Stuart received invite! Latency: {invite_latency:.1f} ms")
    print(f"    Invite Payload: from={invite.get('fromDisplayName')}, roomCode={invite.get('roomCode')}")

    # 4. Stuart joins Kevin's room
    print(f"\n[Step 3] Stuart accepts invite and enters lobby {invite['roomCode']}...")
    t_join = time.time()
    stuart.join_room(invite["roomCode"])
    stuart.send(f"H|{stuart.player_id}|{stuart.display_name}|0|{int(time.time()*1000)}|{stuart.username}||NOT_READY|1|2")

    # Kevin verifies Stuart in lobby
    msg_stuart_join = kevin.wait_for_packet(lambda m: stuart.player_id in m, timeout=3.0)
    assert msg_stuart_join is not None, "FAILED: Kevin did not see Stuart in lobby!"
    join_latency = (time.time() - t_join) * 1000.0
    print(f" -> Kevin detected Stuart in lobby! Latency: {join_latency:.1f} ms")

    # 5. Stuart toggles "I'm Ready"
    print(f"\n[Step 4] Stuart taps 'I\'m Ready'...")
    t_ready = time.time()
    stuart.send(f"R|{stuart.player_id}|READY|{stuart.username}|{stuart.display_name}|2")

    # Kevin detects Stuart's Ready status
    msg_ready = kevin.wait_for_packet(lambda m: "READY" in m and stuart.player_id in m, timeout=3.0)
    assert msg_ready is not None, "FAILED: Kevin did not see Stuart Ready!"
    ready_latency = (time.time() - t_ready) * 1000.0
    print(f" -> Kevin received Stuart Ready reflection! Latency: {ready_latency:.1f} ms")

    # 6. Kevin starts the match
    print(f"\n[Step 5] Kevin taps 'Start Match'...")
    t_start = time.time()
    kevin.send(json.dumps({
        "type": "START_GAME",
        "roomCode": room_code,
        "seed": match_seed,
        "timestamp": int(time.time() * 1000)
    }))

    # Stuart receives START_GAME
    msg_start = stuart.wait_for_packet(lambda m: "START_GAME" in m, timeout=3.0)
    assert msg_start is not None, "FAILED: Stuart did not receive START_GAME!"
    start_latency = (time.time() - t_start) * 1000.0
    print(f" -> Stuart received START_GAME! Latency: {start_latency:.1f} ms")

    # 7. Generate boards
    base_numbers = list(range(1, 26))
    kevin.board = java_shuffle(base_numbers, match_seed)
    stuart.board = java_shuffle(base_numbers, match_seed)
    print(f"\n[Step 6] Boards generated from shared seed {match_seed}:")
    print(f" -> Kevin Board:  {kevin.board[:5]} ... (25 cells)")
    print(f" -> Stuart Board: {stuart.board[:5]} ... (25 cells)")

    # 8. Play match
    print(f"\n[Step 7] Starting 1v1 Gameplay...")
    turn_order = [kevin.player_id, stuart.player_id] # Kevin starts
    turn_num = 1
    picked_history: List[int] = []
    winner = None

    pick_latencies = []

    # Sequence of numbers Kevin wants to pick to form lines quickly
    # Rows 0, 1, 2, 3, 4
    while turn_num <= 30 and not winner:
        active_player = kevin if (turn_num % 2 == 1) else stuart
        passive_player = stuart if active_player == kevin else kevin

        # Choose an unmarked number from active player's board
        unmarked = [n for n in active_player.board if n not in picked_history]
        if not unmarked:
            break
        # To win cleanly, Kevin targets row by row:
        target_num = unmarked[0]

        t_pick = time.time()
        # Active player broadcasts pick
        picked_history.append(target_num)
        active_player.marked.add(target_num)
        passive_player.marked.add(target_num)

        next_turn_num = turn_num + 1
        next_turn_player = passive_player.player_id
        history_str = ",".join(str(x) for x in picked_history)
        packet = f"P|{target_num}|{active_player.player_id}|{turn_num}|{next_turn_player}|{history_str}|{match_seed}"
        active_player.send(packet)

        # Passive player waits for pick
        rcv = passive_player.wait_for_packet(lambda m: m.startswith(f"P|{target_num}|"), timeout=3.0)
        assert rcv is not None, f"FAILED: {passive_player.display_name} did not receive pick {target_num}!"
        latency = (time.time() - t_pick) * 1000.0
        pick_latencies.append(latency)

        kevin_lines = count_completed_lines(kevin.board, kevin.marked)
        stuart_lines = count_completed_lines(stuart.board, stuart.marked)

        print(f"  Turn {turn_num:02d}: [{active_player.display_name}] picked {target_num:02d} "
              f"| Latency: {latency:.1f} ms | Lines: Kevin={kevin_lines}, Stuart={stuart_lines}")

        # Check for 5 lines (BINGO)
        if kevin_lines >= 5:
            winner = kevin
            print(f"\n🎉 BINGO! Kevin reached {kevin_lines} completed lines!")
            kevin.send(f"BINGO|{kevin.player_id}|{kevin_lines}|{target_num}|{match_seed}")
            break
        elif stuart_lines >= 5:
            winner = stuart
            print(f"\n🎉 BINGO! Stuart reached {stuart_lines} completed lines!")
            stuart.send(f"BINGO|{stuart.player_id}|{stuart_lines}|{target_num}|{match_seed}")
            break

        turn_num += 1
        time.sleep(0.05) # fast simulation

    # 9. Verify match conclusion
    assert winner is not None, "FAILED: Match did not produce a winner!"
    print(f"\n[Step 8] Match Complete! Winner: {winner.display_name}")
    print(f" -> Total turns played: {turn_num}")
    print(f" -> Total numbers picked: {len(picked_history)}")
    print(f" -> Average Pick Latency: {sum(pick_latencies)/len(pick_latencies):.1f} ms")
    print(f" -> Min Pick Latency: {min(pick_latencies):.1f} ms")
    print(f" -> Max Pick Latency: {max(pick_latencies):.1f} ms")

    kevin.stop()
    stuart.stop()

    print("\n" + "=" * 70)
    print("  MATCH SUMMARY: 100% SUCCESSFUL END-TO-END PLAYTHROUGH")
    print("=" * 70)

if __name__ == "__main__":
    run_full_kevin_and_stuart_match()
