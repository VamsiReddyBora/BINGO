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
export async function readBody(request, limit = 110000) {
  if (Number(request.headers.get("Content-Length") || 0) > limit)
    throw new Problem(413, "Request is too large");
  const reader = request.body?.getReader();
  const parts = [];
  let size = 0;
  if (reader)
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > limit) {
        await reader.cancel();
        throw new Problem(413, "Request is too large");
      }
      parts.push(value);
    }
  const buffer = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) {
    buffer.set(part, offset);
    offset += part.length;
  }
  const text = new TextDecoder().decode(buffer);
  try {
    return JSON.parse(text || "{}");
  } catch {
    throw new Problem(400, "Invalid JSON");
  }
}
