// Serveur MCP « Sossokouamé » : permet à Claude de consulter ET de modifier votre application
// (nom, boutons de l'accueil, design) en publiant le fichier de configuration lu par l'appli.
// Fonction Supabase : mcp-sossokouame   (Verify JWT : DÉSACTIVÉ, l'accès est protégé par la clé à la fin de l'adresse)
// Adresse à donner à Claude : https://VOTRE-REF.supabase.co/functions/v1/mcp-sossokouame/<CLE>
//
// Chaque modification crée une NOUVELLE ligne dans config_app (historique complet) : on peut toujours revenir en arrière
// avec l'outil config_restaurer. L'appli relit la dernière ligne à chaque ouverture.

const URL_BASE = Deno.env.get("SUPABASE_URL")!;
const CLE_SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;   // fournie automatiquement par Supabase, jamais visible de Claude
// Clé secrète placée à la fin de l'adresse. Pour la changer : secret MCP_CLE (puis mettre à jour l'adresse chez Claude).
const MCP_CLE = Deno.env.get("MCP_CLE") ?? "Zs4Exoy6ypOmEtIOzTGGyCkwQSdEF07xaAmiV5TXmYM";

const VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"];
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type, authorization, mcp-protocol-version, mcp-session-id",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
};

function json(corps: unknown, status = 200) {
  return new Response(JSON.stringify(corps), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

function memeTexte(a: string, b: string) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

async function rest(chemin: string, extra: Record<string, string> = {}, methode = "GET", corps?: unknown) {
  return await fetch(`${URL_BASE}/rest/v1/${chemin}`, {
    method: methode,
    headers: {
      apikey: CLE_SERVICE, Authorization: `Bearer ${CLE_SERVICE}`,
      ...(corps !== undefined ? { "Content-Type": "application/json" } : {}), ...extra,
    },
    body: corps !== undefined ? JSON.stringify(corps) : undefined,
  });
}

async function compter(chemin: string): Promise<number> {
  const r = await rest(chemin, { Prefer: "count=exact", Range: "0-0" });
  await r.text();
  if (!r.ok && r.status !== 206) throw new Error(`${chemin.split("?")[0]} : http ${r.status}`);
  return Number((r.headers.get("content-range") ?? "").split("/")[1] ?? 0);
}

async function lire(chemin: string) {
  const r = await rest(chemin);
  if (!r.ok) throw new Error(`${chemin.split("?")[0]} : http ${r.status}`);
  return await r.json();
}

// ------------------------------------------------------------------ configuration de l'appli (lecture / écriture)

type Obj = Record<string, any>;

const COULEUR = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
const CLES_THEME = ["fond_haut", "fond_bas", "carte", "accent", "violet", "jaune", "texte", "texte2", "rose", "vert", "bleu"];
const ID_TUILE = /^[a-z0-9_-]{1,40}$/i;

function estObjet(x: unknown): x is Obj {
  return typeof x === "object" && x !== null && !Array.isArray(x);
}

async function configCourante(): Promise<{ id: number | null; contenu: Obj; note: string | null; cree_le: string | null }> {
  const l = await lire("config_app?select=id,contenu,note,cree_le&order=id.desc&limit=1");
  if (!l.length) return { id: null, contenu: {}, note: null, cree_le: null };
  return { id: l[0].id, contenu: estObjet(l[0].contenu) ? l[0].contenu : {}, note: l[0].note, cree_le: l[0].cree_le };
}

/** Vérifie le contenu AVANT publication : une erreur ici empêche toute publication (l'appli ne reçoit jamais un fichier cassé). */
function verifier(c: Obj) {
  if (!estObjet(c)) throw new Error("Le fichier de configuration doit être un objet JSON.");
  for (const k of ["titre", "sous_titre", "bandeau"]) {
    if (k in c && (typeof c[k] !== "string" || c[k].length > 120)) throw new Error(`« ${k} » doit être un texte de 120 caractères maximum.`);
  }
  if ("theme" in c) {
    if (!estObjet(c.theme)) throw new Error("« theme » doit être un objet.");
    for (const [k, v] of Object.entries(c.theme)) {
      if (k === "rayon") {
        if (typeof v !== "number" || v < 0 || v > 2.5) throw new Error("theme.rayon : nombre entre 0 et 2.5.");
      } else if (!CLES_THEME.includes(k)) {
        throw new Error(`theme.${k} inconnu. Clés possibles : ${CLES_THEME.join(", ")}, rayon.`);
      } else if (typeof v !== "string" || !COULEUR.test(v)) {
        throw new Error(`theme.${k} : couleur au format #RRGGBB attendue (reçu « ${v} »).`);
      }
    }
  }
  if ("tuiles" in c) {
    if (!Array.isArray(c.tuiles)) throw new Error("« tuiles » doit être une liste.");
    for (const t of c.tuiles) {
      if (!estObjet(t) || typeof t.id !== "string" || !ID_TUILE.test(t.id)) throw new Error("Chaque tuile a un « id » (lettres, chiffres, _ ou -).");
      for (const k of ["c1", "c2"]) if (k in t && (typeof t[k] !== "string" || !COULEUR.test(t[k]))) throw new Error(`Tuile ${t.id} : ${k} doit être #RRGGBB.`);
      for (const k of ["emoji", "titre", "desc", "action"]) if (k in t && typeof t[k] !== "string") throw new Error(`Tuile ${t.id} : ${k} doit être un texte.`);
      if ("titre" in t && t.titre.length > 60) throw new Error(`Tuile ${t.id} : titre trop long (60 max).`);
      if ("desc" in t && t.desc.length > 200) throw new Error(`Tuile ${t.id} : description trop longue (200 max).`);
      if ("masquer" in t && typeof t.masquer !== "boolean") throw new Error(`Tuile ${t.id} : masquer doit être vrai/faux.`);
    }
  }
  if ("ordre" in c && (!Array.isArray(c.ordre) || c.ordre.some((x: unknown) => typeof x !== "string"))) throw new Error("« ordre » doit être une liste d'identifiants.");
  if (JSON.stringify(c).length > 300000) throw new Error("Fichier trop gros.");
}

async function publier(contenu: Obj, note: string) {
  verifier(contenu);
  const r = await rest("config_app?select=id", { Prefer: "return=representation" }, "POST", { contenu, note: note.slice(0, 300) });
  if (!r.ok) throw new Error(`Publication refusée : http ${r.status} ${(await r.text()).slice(0, 200)}`);
  const l = await r.json();
  return { publie: true, numero: l[0]?.id, note, contenu };
}

/** Fusion : un objet est fusionné clé par clé, null supprime la clé, le reste remplace. « tuiles » fusionne par id. */
function fusionner(base: Obj, patch: Obj): Obj {
  const res: Obj = { ...base };
  for (const [k, v] of Object.entries(patch)) {
    if (v === null) { delete res[k]; continue; }
    if (k === "tuiles" && Array.isArray(v)) { res.tuiles = fusionnerTuiles(Array.isArray(base.tuiles) ? base.tuiles : [], v); continue; }
    if (estObjet(v) && estObjet(base[k])) { res[k] = fusionner(base[k], v); continue; }
    res[k] = v;
  }
  return res;
}

function fusionnerTuiles(base: Obj[], patch: Obj[]): Obj[] {
  const res = base.map((t) => ({ ...t }));
  for (const p of patch) {
    const i = res.findIndex((t) => t.id === p?.id);
    if (i >= 0) {
      for (const [k, v] of Object.entries(p)) { if (v === null) delete res[i][k]; else res[i][k] = v; }
    } else res.push({ ...p });
  }
  return res;
}

// ------------------------------------------------------------------ outils

const LECTURE = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const ECRITURE = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false };

const NOTE = { type: "string", description: "Courte note d'historique (facultatif)" };
const COUL = (d: string) => ({ type: "string", description: `${d} — format #RRGGBB` });

const OUTILS = [
  {
    name: "stats",
    description: "Chiffres de l'appli : nombre de comptes, comptes en ligne (actifs depuis moins de 3 minutes), téléchargements, films partagés.",
    inputSchema: { type: "object", properties: {} },
    annotations: LECTURE,
  },
  {
    name: "films_recents",
    description: "Derniers films/vidéos partagés dans l'appli (titre, série, épisode, durée, lien). Recherche possible par titre.",
    inputSchema: {
      type: "object",
      properties: {
        limite: { type: "integer", description: "Nombre de films (1 à 50, défaut 10)" },
        recherche: { type: "string", description: "Mot à chercher dans le titre (facultatif)" },
      },
    },
    annotations: LECTURE,
  },
  {
    name: "derniere_version",
    description: "Dernière version de l'application publiée pour la mise à jour (numéro, date de sortie, nouveautés, obligatoire ou non).",
    inputSchema: { type: "object", properties: {} },
    annotations: LECTURE,
  },
  {
    name: "programme_tv",
    description: "Programme de la TV Kouamé du jour : ordre de passage, titre, durée, début en millisecondes depuis 00h00 (heure du Bénin).",
    inputSchema: { type: "object", properties: {} },
    annotations: LECTURE,
  },
  {
    name: "config_lire",
    description:
      "Lit la configuration actuellement publiée de l'appli (titre, sous-titre, bandeau, tuiles/boutons de l'accueil, ordre, thème/design, pages, annonce). " +
      "À appeler AVANT de modifier quoi que ce soit. Tuiles d'origine : radio, tv, tvkouame, jeux, facebook, films, telechargements, compilateur, analyse, sms, admin, parametres.",
    inputSchema: { type: "object", properties: {} },
    annotations: LECTURE,
  },
  {
    name: "config_historique",
    description: "Liste les dernières configurations publiées (numéro, date, note) pour retrouver une version à restaurer.",
    inputSchema: { type: "object", properties: { limite: { type: "integer", description: "1 à 30, défaut 10" } } },
    annotations: LECTURE,
  },
  {
    name: "definir_nom",
    description:
      "Change le nom de l'application affiché dans l'appli (grand titre de l'accueil et de l'écran de connexion), le sous-titre et/ou le bandeau défilant. " +
      "Ne change PAS le nom sous l'icône du téléphone (celui-là exige une nouvelle version de l'APK). Envoyer seulement les champs à changer ; texte vide = retour à la valeur d'origine.",
    inputSchema: {
      type: "object",
      properties: {
        titre: { type: "string", description: "Nom de l'application (120 caractères max)" },
        sous_titre: { type: "string", description: "Phrase sous le titre (affichée quand aucun nom de compte n'est connu)" },
        bandeau: { type: "string", description: "Message jaune affiché sous le titre" },
        note: NOTE,
      },
    },
    annotations: ECRITURE,
  },
  {
    name: "definir_tuile",
    description:
      "Modifie un bouton (tuile) de l'accueil, ou en crée un nouveau si l'id n'existe pas. Champs : emoji, titre, desc (description), c1/c2 (couleurs du dégradé), " +
      "masquer (cacher/réafficher), action (« page:xxx », « url:https://… » ou « ecran:radio »). Pour remettre un champ d'origine, l'envoyer à null. " +
      "Les tuiles admin et parametres ne peuvent jamais être masquées.",
    inputSchema: {
      type: "object",
      properties: {
        id: { type: "string", description: "Identifiant de la tuile (ex. radio, tv, tvkouame, jeux, facebook, films, sms…)" },
        emoji: { type: ["string", "null"] },
        titre: { type: ["string", "null"], description: "Texte du bouton" },
        desc: { type: ["string", "null"], description: "Petit texte sous le titre" },
        c1: { ...COUL("Couleur de départ du dégradé"), type: ["string", "null"] },
        c2: { ...COUL("Couleur d'arrivée du dégradé"), type: ["string", "null"] },
        masquer: { type: ["boolean", "null"] },
        action: { type: ["string", "null"] },
        note: NOTE,
      },
      required: ["id"],
    },
    annotations: ECRITURE,
  },
  {
    name: "definir_ordre",
    description: "Définit l'ordre des boutons de l'accueil (liste d'identifiants ; ceux non cités passent à la suite). Liste vide = ordre d'origine.",
    inputSchema: { type: "object", properties: { ordre: { type: "array", items: { type: "string" } }, note: NOTE }, required: ["ordre"] },
    annotations: ECRITURE,
  },
  {
    name: "definir_theme",
    description:
      "Change le design global de l'appli (tous les écrans) : couleurs du fond (dégradé fond_haut → fond_bas), des cartes/champs, d'accent, des textes, et l'arrondi des coins (rayon : 0 = angles droits, 1 = d'origine, 2 = très arrondi). " +
      "Valeurs d'origine : fond_haut #6A6E77, fond_bas #5C6068, carte #474B53, accent #FF6B35, violet #7B4DFF, jaune #FFC857 (couleur du titre), texte #FFFFFF, texte2 #E3E5EA, rose #FF4D8D, vert #2E9E73, bleu #1877F2. " +
      "Envoyer seulement ce qui change ; une valeur null remet la couleur d'origine. Le thème s'applique à l'ouverture suivante de l'appli.",
    inputSchema: {
      type: "object",
      properties: {
        fond_haut: { ...COUL("Haut du fond dégradé"), type: ["string", "null"] },
        fond_bas: { ...COUL("Bas du fond dégradé"), type: ["string", "null"] },
        carte: { ...COUL("Cartes, champs de saisie"), type: ["string", "null"] },
        accent: { ...COUL("Couleur d'accent (orange)"), type: ["string", "null"] },
        violet: { ...COUL("Violet"), type: ["string", "null"] },
        jaune: { ...COUL("Jaune : couleur du titre et des surlignages"), type: ["string", "null"] },
        texte: { ...COUL("Texte principal"), type: ["string", "null"] },
        texte2: { ...COUL("Texte secondaire"), type: ["string", "null"] },
        rose: { ...COUL("Rose"), type: ["string", "null"] },
        vert: { ...COUL("Vert (boutons de validation)"), type: ["string", "null"] },
        bleu: { ...COUL("Bleu"), type: ["string", "null"] },
        rayon: { type: ["number", "null"], description: "Arrondi des coins, de 0 à 2.5" },
        note: NOTE,
      },
    },
    annotations: ECRITURE,
  },
  {
    name: "config_modifier",
    description:
      "Modification avancée : fusionne un morceau de JSON dans la configuration (objets fusionnés clé par clé, null supprime une clé, « tuiles » fusionnées par id). " +
      "Sert aux pages (« pages »), annonces (« annonce ») et tout ce que les autres outils ne couvrent pas. Le contenu est vérifié avant publication.",
    inputSchema: { type: "object", properties: { modification: { type: "object", description: "Morceau de JSON à fusionner" }, note: NOTE }, required: ["modification"] },
    annotations: ECRITURE,
  },
  {
    name: "config_restaurer",
    description: "Revient à une configuration précédente (numéro donné par config_historique). Crée une nouvelle entrée : rien n'est perdu. Numéro 0 = tout remettre d'origine (configuration vide).",
    inputSchema: { type: "object", properties: { numero: { type: "integer" }, note: NOTE }, required: ["numero"] },
    annotations: ECRITURE,
  },
];

function nettoyer(a: Obj, exclure: string[]): Obj {
  const o: Obj = {};
  for (const [k, v] of Object.entries(a)) if (!exclure.includes(k) && v !== undefined) o[k] = v;
  return o;
}

async function executer(nom: string, a: Obj) {
  const note = typeof a.note === "string" ? a.note : "";
  if (nom === "stats") {
    const il3min = new Date(Date.now() - 3 * 60_000).toISOString();
    const [comptes, enLigne, telechargements, films] = await Promise.all([
      compter("profils?select=id"),
      compter(`profils?select=id&derniere_activite=gt.${encodeURIComponent(il3min)}`),
      compter("telechargements?select=id"),
      compter("films_facebook?select=id"),
    ]);
    return { comptes, en_ligne: enLigne, telechargements, films };
  }
  if (nom === "films_recents") {
    const limite = Math.min(Math.max(Number(a.limite ?? 10) || 10, 1), 50);
    const mot = String(a.recherche ?? "").replace(/[*,()%]/g, "").trim();
    const filtre = mot ? `&titre=ilike.*${encodeURIComponent(mot)}*` : "";
    return await lire(`films_facebook?select=titre,serie,episode,duree_ms,lien,cree_le&order=cree_le.desc&limit=${limite}${filtre}`);
  }
  if (nom === "derniere_version") {
    try {
      const l = await lire("maj_apk?select=version_name,version_code,note,obligatoire,cree_le&order=version_code.desc&limit=1");
      return l.length ? l[0] : { message: "Aucune version publiée pour le moment." };
    } catch {
      return { message: "Aucune version publiée (la mise à jour de l'appli n'est pas encore installée dans la base)." };
    }
  }
  if (nom === "programme_tv") {
    return await lire("tv_programme?select=ordre,canal,debut_ms,films_facebook(titre,serie,episode,duree_ms)&order=ordre");
  }
  if (nom === "config_lire") {
    const c = await configCourante();
    return { numero: c.id, publiee_le: c.cree_le, note: c.note, contenu: c.contenu };
  }
  if (nom === "config_historique") {
    const limite = Math.min(Math.max(Number(a.limite ?? 10) || 10, 1), 30);
    return await lire(`config_app?select=id,note,cree_le&order=id.desc&limit=${limite}`);
  }
  if (nom === "definir_nom") {
    const patch: Obj = {};
    for (const k of ["titre", "sous_titre", "bandeau"]) {
      if (typeof a[k] === "string") patch[k] = a[k].trim() === "" ? null : a[k].trim();
    }
    if (!Object.keys(patch).length) throw new Error("Rien à changer : donnez titre, sous_titre et/ou bandeau.");
    const c = await configCourante();
    return await publier(fusionner(c.contenu, patch), note || "Nom / textes d'accueil (Claude)");
  }
  if (nom === "definir_tuile") {
    if (typeof a.id !== "string" || !ID_TUILE.test(a.id)) throw new Error("id de tuile invalide.");
    const champs = nettoyer(a, ["note"]);
    if (Object.keys(champs).length < 2) throw new Error("Donnez au moins un champ à modifier (emoji, titre, desc, c1, c2, masquer, action).");
    const c = await configCourante();
    return await publier(fusionner(c.contenu, { tuiles: [champs] }), note || `Bouton ${a.id} (Claude)`);
  }
  if (nom === "definir_ordre") {
    const c = await configCourante();
    return await publier(fusionner(c.contenu, { ordre: Array.isArray(a.ordre) && a.ordre.length ? a.ordre : null }), note || "Ordre des boutons (Claude)");
  }
  if (nom === "definir_theme") {
    const patch = nettoyer(a, ["note"]);
    if (!Object.keys(patch).length) throw new Error("Rien à changer : donnez au moins une couleur ou rayon.");
    const c = await configCourante();
    const res = fusionner(c.contenu, { theme: patch });
    if (estObjet(res.theme) && !Object.keys(res.theme).length) delete res.theme;
    return await publier(res, note || "Design (Claude)");
  }
  if (nom === "config_modifier") {
    if (!estObjet(a.modification)) throw new Error("« modification » doit être un objet JSON.");
    const c = await configCourante();
    return await publier(fusionner(c.contenu, a.modification), note || "Modification avancée (Claude)");
  }
  if (nom === "config_restaurer") {
    const n = Number(a.numero);
    if (!Number.isInteger(n) || n < 0) throw new Error("numero invalide.");
    if (n === 0) return await publier({}, note || "Retour aux réglages d'origine (Claude)");
    const l = await lire(`config_app?select=contenu&id=eq.${n}`);
    if (!l.length) throw new Error(`Configuration n° ${n} introuvable.`);
    return await publier(l[0].contenu, note || `Restauration de la version ${n} (Claude)`);
  }
  throw new Error("Outil inconnu : " + nom);
}

// ------------------------------------------------------------------ protocole MCP (JSON-RPC sur HTTP)

async function traiter(m: any) {
  const id = m?.id;
  if (id === undefined || id === null) return null;   // notification : pas de réponse
  try {
    switch (m.method) {
      case "initialize": {
        const demande = m.params?.protocolVersion;
        return {
          jsonrpc: "2.0", id,
          result: {
            protocolVersion: VERSIONS.includes(demande) ? demande : VERSIONS[0],
            capabilities: { tools: { listChanged: false } },
            serverInfo: { name: "sossokouame", version: "2.0.0" },
          },
        };
      }
      case "ping":
        return { jsonrpc: "2.0", id, result: {} };
      case "tools/list":
        return { jsonrpc: "2.0", id, result: { tools: OUTILS } };
      case "tools/call": {
        try {
          const res = await executer(String(m.params?.name ?? ""), m.params?.arguments ?? {});
          return { jsonrpc: "2.0", id, result: { content: [{ type: "text", text: JSON.stringify(res, null, 2) }] } };
        } catch (e) {
          return { jsonrpc: "2.0", id, result: { isError: true, content: [{ type: "text", text: String((e as Error).message) }] } };
        }
      }
      default:
        return { jsonrpc: "2.0", id, error: { code: -32601, message: "Méthode inconnue : " + m.method } };
    }
  } catch (e) {
    return { jsonrpc: "2.0", id, error: { code: -32603, message: String((e as Error).message) } };
  }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });

  // Accès réservé : la clé secrète doit terminer l'adresse.
  const segments = new URL(req.url).pathname.split("/").filter(Boolean);
  const cle = decodeURIComponent(segments[segments.length - 1] ?? "");
  if (segments.length < 2 || !memeTexte(cle, MCP_CLE)) return json({ error: "non autorisé" }, 401);

  if (req.method !== "POST") return new Response(null, { status: 405, headers: { ...CORS, Allow: "POST" } });

  let corps: any;
  try {
    corps = await req.json();
  } catch {
    return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "JSON invalide" } }, 400);
  }
  if (Array.isArray(corps)) {
    const reponses = (await Promise.all(corps.map(traiter))).filter((x) => x !== null);
    return reponses.length ? json(reponses) : new Response(null, { status: 202, headers: CORS });
  }
  const rep = await traiter(corps);
  return rep ? json(rep) : new Response(null, { status: 202, headers: CORS });
});
