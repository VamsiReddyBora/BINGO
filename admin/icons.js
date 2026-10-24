const paths = {
  players: [
    "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
    "M16 3a4 4 0 0 1 0 8",
    "M22 21v-2a4 4 0 0 0-3-3.87",
  ],
  rooms: ["M3 4h18v14H3z", "M8 22h8", "M12 18v4"],
  announcements: ["M3 10v4h4l10 4V6L7 10z", "M7 14l1 7h3l-1-6", "M21 9v6"],
  release: ["M12 16V3", "M7 8l5-5 5 5", "M4 16v5h16v-5"],
  activity: ["M3 12h4l3-8 4 16 3-8h4"],
  settings: [
    "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8",
    "M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z",
  ],
  fingerprint: [
    "M4 8a9 9 0 0 1 16 0",
    "M7 5a6 6 0 0 1 11 5v3",
    "M3 12v2",
    "M6 11v4a10 10 0 0 1-1 4",
    "M9 11a3 3 0 0 1 6 0v4a16 16 0 0 1-1 6",
    "M12 11v5a17 17 0 0 1-1 5",
    "M18 16a19 19 0 0 1-1 5",
    "M21 11v3",
  ],
  shield: ["M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z", "M8 12l3 3 5-6"],
  lock: ["M5 10h14v11H5z", "M8 10V7a4 4 0 0 1 8 0v3", "M12 14v3"],
  refresh: ["M20 7V3l-4 4", "M20 7a8 8 0 1 0 0 10"],
  moon: ["M20 15a8 8 0 0 1-11-11 8 8 0 1 0 11 11"],
  menu: ["M4 6h16", "M4 12h16", "M4 18h16"],
  arrow: ["M5 12h14", "M14 7l5 5-5 5"],
  search: ["M21 21l-5-5"],
  table: ["M3 4h18v16H3z", "M3 9h18", "M8 9v11"],
  grid: ["M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z", "M14 14h7v7h-7z"],
};
export function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  for (const [k, v] of Object.entries({
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    "stroke-width": "1.65",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
    "aria-hidden": "true",
  }))
    svg.setAttribute(k, v);
  for (const d of paths[name] || paths.shield) {
    const p = document.createElementNS(svg.namespaceURI, "path");
    p.setAttribute("d", d);
    svg.append(p);
  }
  if (name === "players" || name === "search") {
    const circle = document.createElementNS(svg.namespaceURI, "circle");
    circle.setAttribute("cx", name === "players" ? "9" : "10.5");
    circle.setAttribute("cy", name === "players" ? "7" : "10.5");
    circle.setAttribute("r", name === "players" ? "4" : "6.5");
    svg.append(circle);
  }
  return svg;
}
export function decorate() {
  for (const node of document.querySelectorAll("[data-icon]"))
    node.replaceChildren(icon(node.dataset.icon));
}
