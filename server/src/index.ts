import { buildApp } from "./app.js";
import { config } from "./config.js";

const { app } = await buildApp();

const shutdown = async () => {
  await app.close();
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

await app.listen({ port: config.port, host: config.host });
console.log(`FocusQuest server listening on ${config.tlsCert ? "https" : "http"}://${config.host}:${config.port}`);
