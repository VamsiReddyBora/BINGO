import { Problem } from "./model.js";
export const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
export async function readBody(request) {
  const text = await request.text();
  if (text.length > 110000) {
    throw new Problem(413, "Request is too large");
  }
  try {
    return JSON.parse(text || "{}");
  } catch {
    throw new Problem(400, "Invalid JSON");
  }
}
