import { Board, Cell, LineCoordinate } from '../types/models';

export class BingoEngine {
  /**
   * Generates a 5x5 board containing numbers 1 to 25.
   */
  public static generateBoard(size: number = 5, seed?: number): Board {
    const total = size * size;
    const numbers = Array.from({ length: total }, (_, i) => i + 1);

    // Shuffle numbers
    if (seed !== undefined && seed !== 0) {
      let currentSeed = seed;
      const pseudoRandom = () => {
        currentSeed = (currentSeed * 9301 + 49297) % 233280;
        return currentSeed / 233280;
      };
      for (let i = numbers.length - 1; i > 0; i--) {
        const j = Math.floor(pseudoRandom() * (i + 1));
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
}
