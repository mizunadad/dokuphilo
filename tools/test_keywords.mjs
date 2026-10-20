// 煩悩判定キーワードと辞典データの回帰テスト
// 実行: node tools/test_keywords.mjs
//
// 1. 例文ごとの煩悩判定が期待どおりか（ADR-014）
// 2. 辞典データの整合（ADR-012・ADR-013・ADR-015）
//    - どの煩悩 × シチュエーションでも、異なる賢者のセリフが3件以上引けるか
//    - punchline が書き方のルールを満たすか
//    - 登場賢者が DokuPhilo_adv.md の収録者のみか
//
// index.html の埋め込み JSON（dokuphilo-data）と、index.html 内の判定関数
// （countHits / detectBest）をそのまま取り出して使う。判定ロジックを
// このファイルに書き写さないので、index.html を直せばテストにも反映される。

import { readFileSync } from "node:fs";

const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");

// ---- 埋め込み JSON ----
const jsonMatch = html.match(
  /<script type="application\/json" id="dokuphilo-data">([\s\S]*?)<\/script>/
);
if (!jsonMatch) throw new Error("index.html に dokuphilo-data が見つかりません");
const DATA = JSON.parse(jsonMatch[1]);

// ---- 判定関数（index.html から取り出す）----
const start = html.indexOf("function countHits(");
const end = html.indexOf("// 別の煩悩には絶対に広げない");
if (start === -1 || end === -1 || end <= start) {
  throw new Error("index.html から判定関数を取り出せません（countHits / detectBest の位置を確認）");
}
const detectBest = new Function(html.slice(start, end) + "\nreturn detectBest;")();

// ---- 例文（expected: flex / bluff / anxious / null=煩悩なし）----
const CASES = [
  // flex（自己顕示）
  { text: "私より上手く思考できる存在は知らないよ", expected: "flex" },
  { text: "昨日も社長とサウナで、新規事業のアドバイスを求められちゃってさ", expected: "flex" },
  { text: "自慢じゃないけど、今期の売上トップは僕だよ", expected: "flex" },
  { text: "俺くらいになると、プレゼンの練習なんていらないね", expected: "flex" },
  { text: "デート中に俺の年収の話をしちゃった", expected: "flex" },
  { text: "私クラスになると、準備なんていらないの", expected: "flex" },
  // 取りこぼし集 M1・M4・M6（組み合わせ条件で拾う。ADR-016）
  { text: "今日若手に説教したら、先生って呼ばれちゃってさ", expected: "flex" },
  { text: "社長くらいやったら僕でもできるけど。", expected: "flex" },
  { text: "取引先の昇進の推薦者になってって言われちゃったよ", expected: "flex" },
  // 取りこぼし集 M7（キーワードの活用違い。ちゃって・ちゃった・ちゃう を語幹で拾う）
  { text: "社長とサウナで、新規事業のアドバイスを求められちゃったよ", expected: "flex" },

  // bluff（知ったかぶり）
  { text: "定義も曖昧なまま、会議でWeb3とシナジーの重要性を語った", expected: "bluff" },
  { text: "読んでもいない本を、読んだふりして感想を言った", expected: "bluff" },
  { text: "よく知らないのに、知ってる知ってると相づちを打った", expected: "bluff" },
  { text: "専門用語を並べて、それっぽく語っておいた", expected: "bluff" },
  { text: "意味も分からず使ってる横文字が多い", expected: "bluff" },

  // anxious（過剰迎合）
  { text: "嫌われたくなくて、相手の意見に全部笑顔で同調しちゃった", expected: "anxious" },
  { text: "上司の顔色をうかがって、結局何も言えなかった", expected: "anxious" },
  { text: "飲み会の誘いをまた断れなかった", expected: "anxious" },
  { text: "本音が言えないまま、愛想笑いで乗り切った", expected: "anxious" },
  { text: "周りに流されて、反対できなかった", expected: "anxious" },
  // 取りこぼし集 M10（話し言葉の崩れ。助詞「を」の脱落）
  { text: "空気読んで話合わせちゃった", expected: "anxious" },

  // 煩悩なし（普通の文。誤検知しないこと）
  { text: "今日は雨ですね", expected: null },
  { text: "今日はいい天気なので、散歩してから本を読みます", expected: null },
  // 根拠を示すのは正当な行為で、知ったかぶりではない。「エビデンス」という語だけでは判定しない
  { text: "会議でエビデンスを示した", expected: null },
  { text: "彼は私より背が高い", expected: null },
  { text: "顔色が悪いので早めに帰ります", expected: null },
  { text: "同調圧力について本で読んだ", expected: null },
  { text: "意見を求められて答えた", expected: null },
  { text: "ご存知の通り、来週は祝日です", expected: null },
  { text: "Web3の記事を読んだ", expected: null },
  { text: "年収の統計を調べた", expected: null },
  { text: "うろ覚えなので確認します", expected: null },
  { text: "空気を読まない発言で場が和んだ", expected: null },
  // 自虐の文に自慢のツッコミをしない（「私ほど」「俺ほど」はキーワードに入れない）
  { text: "私ほど不器用な人はいない", expected: null },
  { text: "俺ほど運の悪い奴はいない", expected: null },
  // 取りこぼし集「キーワード化した場合の誤検知の例」（ADR-016）
  { text: "上司に呼ばれちゃって", expected: null },
  { text: "説明が下手で、誰も理解してもらえなかった", expected: null },
  { text: "このアプリならなんでもできちゃう", expected: null },
  { text: "僕でもできる簡単レシピ", expected: null },
  { text: "こんなに簡単なことができないなんて", expected: null },
  { text: "文句を言われちゃった", expected: null },
  // 組み合わせ条件の歯止め: 呼び出し・見下しのない「僕でもできる」
  { text: "先生に呼ばれちゃって、職員室に行った", expected: null },
  { text: "社長に教わった方法なら僕でもできる", expected: null },
  // 「話合わせ」をキーワードにしない理由（「電話合わせて」に一致する）
  { text: "メールと電話合わせて5件対応した", expected: null },
];

// ---- キーワード数と重複のチェック ----
const problems = [];
const seen = new Map();
for (const [key, bonno] of Object.entries(DATA.bonnos)) {
  const n = bonno.keywords.length;
  if (n < 15 || n > 20) problems.push(`${key}: キーワードが ${n} 語（15〜20語にする）`);
  for (const k of bonno.keywords) {
    const norm = k.toLowerCase();
    if (seen.has(norm)) problems.push(`キーワード「${k}」が ${seen.get(norm)} と ${key} で重複`);
    else seen.set(norm, key);
  }
  // 組み合わせ条件（ADR-016）。キーワード数には数えない
  const comboIds = new Set();
  for (const c of bonno.combos || []) {
    if (comboIds.has(c.id)) problems.push(`${key}: combo id が重複: ${c.id}`);
    comboIds.add(c.id);
    const ok = Array.isArray(c.all) && c.all.length >= 2 && c.all.every((g) => Array.isArray(g) && g.length > 0);
    if (!ok) problems.push(`${key} の combo ${c.id}: all は空でないグループを2つ以上にする`);
  }
}

// ---- 辞典データの整合チェック ----
const lines = DATA.lines;
const sageIds = Object.keys(DATA.sages);

// ADR-015: council は使わない
if ("council" in DATA) problems.push("council が残っています（ADR-015 で廃止）");

// 登場賢者は DokuPhilo_adv.md の収録者（「### 名前（」の見出し）のみ
const adv = readFileSync(new URL("../DokuPhilo_adv.md", import.meta.url), "utf8");
const entrySection = adv.slice(adv.indexOf("## 哲学者エントリ"));
const advNames = [...entrySection.matchAll(/^### ([^（\n]+)（/gm)].map((m) => m[1].trim());
if (advNames.length === 0) problems.push("DokuPhilo_adv.md から賢者の見出しを読み取れません");
for (const id of sageIds) {
  const sage = DATA.sages[id];
  if (!advNames.includes(sage.name)) problems.push(`賢者 ${id}（${sage.name}）は DokuPhilo_adv.md の収録者ではありません`);
  if (!Array.isArray(sage.affinity) || sage.affinity.length === 0) problems.push(`賢者 ${id} に affinity がありません`);
  for (const b of sage.affinity || []) {
    if (!DATA.bonnos[b]) problems.push(`賢者 ${id} の affinity に未定義の煩悩 ${b}`);
  }
}

// 他の思想家の著書名・有名な言い回しの混入（見つかったものを足していく）
const FORBIDDEN_PHRASES = ["自由からの逃走"];

// 各セリフの書き方
const lineIds = new Set();
const punchlines = new Map(); // ADR-025: 同じ punchline を2件置かない
for (const l of lines) {
  if (lineIds.has(l.id)) problems.push(`line id が重複: ${l.id}`);
  lineIds.add(l.id);
  const sage = DATA.sages[l.sage];
  if (!sage) { problems.push(`${l.id}: 未定義の賢者 ${l.sage}`); continue; }
  if (!DATA.bonnos[l.bonno]) problems.push(`${l.id}: 未定義の煩悩 ${l.bonno}`);
  if (!DATA.situations[l.situation]) problems.push(`${l.id}: 未定義のシチュエーション ${l.situation}`);
  if (!sage.affinity.includes(l.bonno)) problems.push(`${l.id}: 賢者の affinity にない煩悩のセリフ（引き当てられない）`);
  const p = l.punchline || "";
  if (p.length === 0) problems.push(`${l.id}: punchline が空`);
  if (p.length > 0) {
    if (punchlines.has(p)) problems.push(`${l.id}: punchline が ${punchlines.get(p)} と重複`);
    else punchlines.set(p, l.id);
  }
  if (Array.from(p).length > 100) problems.push(`${l.id}: punchline が100文字超（${Array.from(p).length}文字）`);
  if (p.startsWith("「") && p.endsWith("」")) problems.push(`${l.id}: punchline の外側が「」で囲まれている`);
  if (/[『』]/.test(p)) problems.push(`${l.id}: punchline に『』が含まれている`);
  if (!l.study || !l.study.core || !l.study.reasoning || !l.study.praxis) problems.push(`${l.id}: study の3項目が揃っていない`);
  const all = [p, l.study?.core, l.study?.reasoning, l.study?.praxis].join("\n");
  for (const f of FORBIDDEN_PHRASES) if (all.includes(f)) problems.push(`${l.id}: 「${f}」は他の思想家の言い回し`);
}

// 既存の9件（ADR-015 で残すと決めたもの）が残っているか
for (const s of ["socrates", "nietzsche", "sartre"]) {
  for (const b of ["flex", "bluff", "anxious"]) {
    const id = `${s}-${b}-business-01`;
    if (!lineIds.has(id)) problems.push(`既存のセリフ ${id} がありません`);
  }
}

// どの煩悩 × シチュエーションでも、異なる賢者のセリフが3件以上引けるか
const coverage = [];
for (const b of Object.keys(DATA.bonnos)) {
  for (const s of Object.keys(DATA.situations)) {
    const sages = new Set(
      lines
        .filter((l) => l.bonno === b && l.situation === s && DATA.sages[l.sage]?.affinity.includes(b))
        .map((l) => l.sage)
    );
    coverage.push(`${b} × ${s}: ${sages.size}名`);
    if (sages.size < 3) problems.push(`${b} × ${s}: 異なる賢者のセリフが ${sages.size} 件（3件以上必要）`);
  }
}

// ADR-025: 各賢者 × affinity の各煩悩 × 各シチュエーションに1件以上あるか
for (const id of sageIds) {
  for (const b of DATA.sages[id].affinity || []) {
    for (const s of Object.keys(DATA.situations)) {
      if (!lines.some((l) => l.sage === id && l.bonno === b && l.situation === s)) {
        problems.push(`賢者 ${id} × ${b} × ${s}: セリフがない（1件以上必要）`);
      }
    }
  }
}

// ---- 実行 ----
const label = (v) => (v === null ? "煩悩なし" : v);
let failed = 0;
for (const c of CASES) {
  const actual = detectBest(c.text, DATA.bonnos);
  const ok = actual === c.expected;
  if (!ok) failed++;
  console.log(`${ok ? "OK  " : "NG  "} 期待=${label(c.expected).padEnd(8)} 結果=${label(actual).padEnd(8)} ${c.text}`);
}

console.log(`\n辞典データ: 賢者 ${sageIds.length} 名、セリフ ${lines.length} 件`);
console.log(coverage.join(" / "));
for (const p of problems) console.log(`NG   ${p}`);

const total = CASES.length;
console.log(`\n${total - failed}/${total} 件一致、データの問題 ${problems.length} 件`);
if (failed > 0 || problems.length > 0) process.exit(1);
