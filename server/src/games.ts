/** Mirrors GameId in the Android app. */
export type Metric = "ATTENTION" | "CALM";

export interface Game {
  id: string;
  title: string;
  metric: Metric;
  skill: string;
  style: string;
}

export const GAMES: Game[] = [
  { id: "ROCKET", title: "Sky Rocket", metric: "ATTENTION", skill: "Focus", style: "Sustained" },
  { id: "DRIVE", title: "Night Drive", metric: "ATTENTION", skill: "Focus", style: "Sustained" },
  { id: "MOSAIC", title: "Picture Reveal", metric: "ATTENTION", skill: "Focus", style: "Sustained" },
  { id: "GARDEN", title: "Bloom Garden", metric: "ATTENTION", skill: "Focus", style: "Goal-based" },
  { id: "BRIDGE", title: "Bridge Builder", metric: "ATTENTION", skill: "Focus", style: "Goal-based" },
  { id: "ARCHERY", title: "Archer's Focus", metric: "ATTENTION", skill: "Focus", style: "Bursts" },
  { id: "BALLOON", title: "Calm Balloon", metric: "CALM", skill: "Calm", style: "Relaxation" },
  { id: "LANTERN", title: "Lantern Lake", metric: "CALM", skill: "Calm", style: "Relaxation" },
];

const byId = new Map(GAMES.map((g) => [g.id, g]));

export function game(id: string): Game {
  return byId.get(id) ?? { id, title: id, metric: "ATTENTION", skill: "Focus", style: "Other" };
}

export const isGame = (id: unknown): id is string => typeof id === "string" && byId.has(id);

export const AVATAR_COLORS = ["#7C5CFF", "#2EE6A6", "#FFB547", "#FF5C7A", "#5CC8FF", "#E36CFF"];
