import { Board, Cell, LineCoordinate } from '../types/models';

/**
 * Exact implementation of Kotlin stdlib Random(seed: Long) (XorWowRandom)
 * ensuring 100% bit-for-bit identical board generation between Android and Web.
 */
export class KotlinRandom {
  private x: number;
  private y: number;
  private z: number;
  private w: number;
  private v: number;
  private addend: number;

  constructor(seed: number | bigint) {
    const seedBig = BigInt(seed);
    const seed1 = Number(BigInt.asIntN(32, seedBig));
    const seed2 = Number(BigInt.asIntN(32, seedBig >> 32n));

    this.x = seed1 | 0;
    this.y = seed2 | 0;
    this.z = 0;
    this.w = 0;
    this.v = (~seed1) | 0;
    this.addend = ((seed1 << 10) ^ (seed2 >>> 4)) | 0;

    if ((this.x | this.y | this.z | this.w | this.v) === 0) {
      this.w = 1;
    }
    for (let i = 0; i < 64; i++) {
      this.nextInt();
    }
  }

  public nextInt(): number {
    let t = this.x;
    t = (t ^ (t >>> 2)) | 0;
    this.x = this.y;
    this.y = this.z;
    this.z = this.w;
    const v0 = this.v;
    this.w = v0;
    t = ((t ^ (t << 1)) ^ v0 ^ (v0 << 4)) | 0;
    this.v = t;
    this.addend = (this.addend + 362437) | 0;
    return (t + this.addend) | 0;
  }

  public nextBits(bitCount: number): number {
    return ((this.nextInt() >>> (32 - bitCount)) & ((-bitCount) >> 31)) | 0;
  }

  public nextIntUntil(until: number): number {
    const n = until | 0;
    if ((n & -n) === n) {
      const fastLog2 = 31 - Math.clz32(n);
      return this.nextBits(fastLog2);
    }
    let v: number, bits: number;
    do {
      bits = this.nextInt() >>> 1;
      v = bits % n;
    } while (bits - v + (n - 1) < 0);
    return v;
  }
}

export class BingoEngine {
  /**
   * Generates a 5x5 board containing numbers 1 to 25.
   * Matches Kotlin BingoEngine.generateBoard with exact PRNG shuffle when seed is provided.
   */
  public static generateBoard(size: number = 5, seed?: number | bigint): Board {
    const total = size * size;
    const numbers = Array.from({ length: total }, (_, i) => i + 1);

    // Shuffle numbers
    if (seed !== undefined && seed !== 0) {
      const rng = new KotlinRandom(seed);
      for (let i = numbers.length - 1; i > 0; i--) {
        const j = rng.nextIntUntil(i + 1);
        [numbers[i], numbers[j]] = [numbers[j], numbers[i]];
      }
    } else {
      for (let i = numbers.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [numbers[i], numbers[j]] = [numbers[j], numbers[i]];
      }
    }

    const cells: Cell[] = [];
    for (let r = 0; r < size; r++) {
      for (let c = 0; c < size; c++) {
        cells.push({
          row: r,
          col: c,
          number: numbers[r * size + c],
          markState: { type: 'Unmarked' },
          isPartOfCompletedLine: false,
          isRecentPick: false
        });
      }
    }

    return {
      size,
      cells,
      completedLines: [],
      targetLines: 5
    };
  }

  /**
   * Marks cell containing [number] and updates completed lines.
   */
  public static markCell(
    board: Board,
    pickedNumber: number,
    pickedByPlayerId: string,
    isOwnPick: boolean,
    turnNumber: number
  ): { board: Board; newLinesCompleted: number } {
    const size = board.size;
    const oldLineCount = board.completedLines.length;

    // 1. Mark target cell
    const updatedCells: Cell[] = board.cells.map(cell => {
      if (cell.number === pickedNumber) {
        return {
          ...cell,
          markState: {
            type: 'Marked',
            pickedByPlayerId,
            isOwnPick,
            turnNumber
          },
          isRecentPick: true
        };
      } else {
        return {
          ...cell,
          isRecentPick: false
        };
      }
    });

    // 2. Detect completed lines
    const isMarked = (r: number, c: number) => {
      const idx = r * size + c;
      return updatedCells[idx].markState.type === 'Marked';
    };

    const completedLines: LineCoordinate[] = [];
    const winningIndices = new Set<number>();

    // Check rows
    for (let r = 0; r < size; r++) {
      let complete = true;
      for (let c = 0; c < size; c++) {
        if (!isMarked(r, c)) {
          complete = false;
          break;
        }
      }
      if (complete) {
        completedLines.push({ type: 'ROW', index: r });
        for (let c = 0; c < size; c++) winningIndices.add(r * size + c);
      }
    }

    // Check columns
    for (let c = 0; c < size; c++) {
      let complete = true;
      for (let r = 0; r < size; r++) {
        if (!isMarked(r, c)) {
          complete = false;
          break;
        }
      }
      if (complete) {
        completedLines.push({ type: 'COLUMN', index: c });
        for (let r = 0; r < size; r++) winningIndices.add(r * size + c);
      }
    }

    // Check main diagonal (top-left to bottom-right)
    let mainDiagComplete = true;
    for (let i = 0; i < size; i++) {
      if (!isMarked(i, i)) {
        mainDiagComplete = false;
        break;
      }
    }
    if (mainDiagComplete) {
      completedLines.push({ type: 'MAIN_DIAGONAL', index: 0 });
      for (let i = 0; i < size; i++) winningIndices.add(i * size + i);
    }

    // Check anti diagonal (top-right to bottom-left)
    let antiDiagComplete = true;
    for (let i = 0; i < size; i++) {
      if (!isMarked(i, size - 1 - i)) {
        antiDiagComplete = false;
        break;
      }
    }
    if (antiDiagComplete) {
      completedLines.push({ type: 'ANTI_DIAGONAL', index: 0 });
      for (let i = 0; i < size; i++) winningIndices.add(i * size + (size - 1 - i));
    }

    // 3. Mark winning line cells
    const finalCells = updatedCells.map((cell, idx) => ({
      ...cell,
      isPartOfCompletedLine: winningIndices.has(idx)
    }));

    const newLinesCompleted = Math.max(0, completedLines.length - oldLineCount);

    return {
      board: {
        ...board,
        cells: finalCells,
        completedLines
      },
      newLinesCompleted
    };
  }

  /**
   * Smart AI Move selection based on line completion priority.
   */
  public static pickAiMove(board: Board): number {
    const size = board.size;
    const unmarked = board.cells.filter(c => c.markState.type === 'Unmarked');
    if (unmarked.length === 0) return -1;

    let bestCell = unmarked[0];
    let maxPotential = -1;

    for (const cell of unmarked) {
      let potential = 0;
      // Row count
      const rowMarked = board.cells.filter(c => c.row === cell.row && c.markState.type === 'Marked').length;
      potential += rowMarked * rowMarked;

      // Col count
      const colMarked = board.cells.filter(c => c.col === cell.col && c.markState.type === 'Marked').length;
      potential += colMarked * colMarked;

      // Diag 1
      if (cell.row === cell.col) {
        const diagMarked = board.cells.filter(c => c.row === c.col && c.markState.type === 'Marked').length;
        potential += diagMarked * diagMarked;
      }

      // Diag 2
      if (cell.row + cell.col === size - 1) {
        const antiDiagMarked = board.cells.filter(c => c.row + c.col === size - 1 && c.markState.type === 'Marked').length;
        potential += antiDiagMarked * antiDiagMarked;
      }

      if (potential > maxPotential) {
        maxPotential = potential;
        bestCell = cell;
      }
    }

    return bestCell.number;
  }

  /**
   * Deterministic & Collision-Free Player Board Seed Derivation matching Android LobbyLifecycleEngine.
   */
  public static resolvePlayerBoardSeed(
    baseSeed: number | bigint,
    player: { username?: string; id?: string; isHost?: boolean },
    index: number
  ): bigint {
    const pUser = (player.username || '').trim().toLowerCase().replace(/^@/, '').replace(/^u_/, '');
    const pId = (player.id || '').trim().toLowerCase().replace(/^u_/, '');
    const clean = pUser || pId;
    let h = 1125899906842597n;
    for (let i = 0; i < clean.length; i++) {
      h = BigInt.asIntN(64, 31n * h + BigInt(clean.charCodeAt(i)));
    }
    const roleMultiplier = player.isHost ? 100003n : BigInt(index + 1) * 200009n;
    const baseBig = BigInt(baseSeed);
    let mixed = BigInt.asIntN(64, baseBig ^ h ^ roleMultiplier);
    if (mixed === 0n) {
      mixed = BigInt.asIntN(64, baseBig + BigInt(index + 1) * 37n);
    }
    return mixed;
  }
}
