(ns yard-ops.contract-test
  "yard-ops の面どうしの契約を固定する。

  この repo は state-less な edge BFF であって、計算は AgentGateway MCP と
  pod 側の LangServer に居る。したがってこの repo の実体はアルゴリズムではなく、
  **複数の面が同じ actor・同じ lexicon・同じ能力表・同じ配備先について同じことを
  言っている**という合意である:

    src/app.ts                          thin edge（health probe・NSID 転送・内部秘密）
    src/xrpc-agentgateway-proxy.ts       保存された旧 XRPC 面（MCP router 転送。配線されていない）
    cljs/src/yard_ops/app.cljs           landing page（reagent + re-frame + jp-go-dds、自己記述を持つ）
    wrangler.jsonc                       配備（name / routes / vars / assets。main は無い）
    kotodama.jsonld                      actor identity（DID / nanoid / capabilities）
    package.json / cljs/package.json     名前
    README.edn                           repo の名乗り
    migration.edn                        wave 1（etzhayyim/root からの抽出）の宣言。wave 2 は動かさない
    NOTICE                               配布条件

  どの面も他を import していないので、片方だけ動いた drift は throw しない
  —— identity 文書と worker が別の nanoid を名乗っても配備は成功し、
  did:web の解決や route の逆引きが空振りして初めて分かる。

  ## 2026-09-07: Svelte → ClojureScript 移行（wave 2）

  この repo のフロントエンドは SvelteKit（`svelte/`）から reagent + re-frame +
  jp-go-dds（`cljs/`）へ移行した。backend TypeScript（`src/app.ts`）は無改造。
  `svelte/` は 7 ファイルとも削除済み。

  移行前は `wrangler.main` が SvelteKit の Cloudflare adapter build を指し、
  その build が `svelte/src/routes/+page.svelte`（landing page）と
  `svelte/src/routes/xrpc/[...path]/+server.ts`（XRPC proxy）の両方を
  1 つの worker にまとめて配備していた。移行後は `wrangler.jsonc` に
  `main` が無く、**静的 assets（`cljs/public`）だけが配備される** ——
  landing page は `cljs/src/yard_ops/app.cljs` として配備されるが、
  XRPC proxy は body 無改造のまま `src/xrpc-agentgateway-proxy.ts` へ
  移設されただけで、**どこからも参照されておらず配線されていない**
  （`@sveltejs/kit` に依存しているので今のままでは動かせない。再配線は
  この移行のスコープ外の製品判断）。

  `migration.edn` は wave 1（`etzhayyim/root` からの最初の抽出、12 ファイル）
  だけを記述する別の provenance イベントなので、この移行では書き換えない
  —— 書き換えると `:source` の revision/tree/bytes が指す実際の抽出内容と
  ずれる。wave 2 が足したファイル（`cljs/**` と
  `src/xrpc-agentgateway-proxy.ts`）は下の `wave-2-migration-additions` に
  この test ファイル側で宣言する。

  ## 配備されるのは src/app.ts ではない（2026-08-26 実測、2026-09-07 更新）

  `src/app.ts` は今も**配備の実行経路に入っていない**。`main` 自体が無く
  なったので、`src/app.ts` と `src/xrpc-agentgateway-proxy.ts` はどちらも
  配備されない、という点では同じになった。ただし信頼モデル・上流・methods
  の違いは記録として残す価値があるので、保存された旧 XRPC 面についての
  `deployed-*` 群は引き続きその body の性質（trust 委譲・allowlist 不在・
  method 面）を pin する —— 動いていた頃の契約を、動かなくなった今も
  デグレさせずに保つため:

    | | src/app.ts（配備されない） | src/xrpc-agentgateway-proxy.ts（配備されない） |
    |---|---|---|
    | 上流 | dispatcher.etzhayyim.com | mcp.etzhayyim.com（MCP router） |
    | NSID | `com.etzhayyim.apps.yardOps.` だけ通す | **allowlist 無し。任意の path を転送** |
    | 認証 | 自分で `x-internal-secret` を付ける | 呼び手の header をそのまま委譲 |
    | method | POST と GET | **POST と OPTIONS のみ（GET は無い）** |

  ## 抽出の床

  各抽出は見つからなければ throw する。**『抽出できなかった』が
  『合意している』と同じ顔をしてはならない** —— 正規表現は実装が変わると
  静かに空振りし、空振りは合格と同じ緑を返すからである。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cljs.reader :as reader]
            ["fs" :as fs]
            ["node:child_process" :as cp]))

;; ─── 抽出（見つからなければ throw） ─────────────────────────────────────

(defn- slurp-file [path]
  (try (fs/readFileSync path "utf8")
       (catch :default e
         (throw (ex-info (str "read failed: " path " — 面が消えた repo を緑にしない")
                         {:path path} e)))))

(defn- extract-1
  "re の第 1 group を返す。見つからなければ throw —— 空振りを緑にしない。"
  [src re path what]
  (or (second (re-find re src))
      (throw (ex-info (str "extraction failed: " what " not found in " path)
                      {:path path :what what}))))

(defn- json-file
  "JSON を読む。wrangler.jsonc は今日コメントを持つので、行頭コメントだけ
  落としてから parse する（行内 `//` は URL を壊すので触らない）。"
  [path]
  (let [raw (slurp-file path)
        stripped (->> (str/split-lines raw)
                      (remove #(str/starts-with? (str/trim %) "//"))
                      (str/join "\n"))]
    (js->clj (js/JSON.parse stripped) :keywordize-keys false)))

(defn- edn-file [path] (reader/read-string (slurp-file path)))

(defn- git-out
  "git の出力。答えられなければ throw —— 『測れなかった』を『違反 0 件』にしない。"
  [args what]
  (let [r (cp/spawnSync "git" (clj->js args) #js {:encoding "utf8"})]
    (when-not (zero? (or (.-status r) 1))
      (throw (ex-info (str "git " (str/join " " args) " failed — " what " は未測定であって clean ではない")
                      {:stderr (.-stderr r)})))
    (.-stdout r)))

(defn- head-files
  "HEAD が持つファイルの一覧。**working tree ではなく commit を測る** ——
  migration.edn の主張は『この repository は抽出物 + 宣言した追加物である』で
  あって、いま手元で編集中のバイト列についてではない。"
  []
  (->> (str/split-lines (git-out ["ls-tree" "-r" "--name-only" "HEAD"] "migration identity"))
       (remove str/blank?)
       vec))

(defn- head-size
  "HEAD における blob のバイト数。"
  [path]
  (js/parseInt (str/trim (git-out ["cat-file" "-s" (str "HEAD:" path)] (str "size of " path))) 10))

;; ─── 面 ────────────────────────────────────────────────────────────────

(def edge-path "src/app.ts")
(def deployed-path "src/xrpc-agentgateway-proxy.ts")
(def page-path "cljs/src/yard_ops/app.cljs")
(def wrangler-path "wrangler.jsonc")
(def kotodama-path "kotodama.jsonld")

(def edge (slurp-file edge-path))
(def deployed (slurp-file deployed-path))
(def page (slurp-file page-path))
(def wrangler (json-file wrangler-path))
(def kotodama (json-file kotodama-path))
(def pkg (json-file "package.json"))
(def cljs-pkg (json-file "cljs/package.json"))
(def readme (edn-file "README.edn"))
(def migration (edn-file "migration.edn"))

(def wrangler-vars (get wrangler "vars"))
(def kotodama-profile (get kotodama "profile"))

(defn- ts-const [src name*]
  (extract-1 src (re-pattern (str "const\\s+" name* "\\s*=\\s*\"([^\"]+)\"")) edge-path name*))

(def actor-did (ts-const edge "ACTOR_DID"))
(def nsid-prefix (ts-const edge "NSID_PREFIX"))

(def edge-methods
  "health probe が名乗る 4 method。"
  (-> (extract-1 edge #"methods:\s*\[([^\]]+)\]" edge-path "methods")
      (str/split #",")
      (->> (map #(str/replace (str/trim %) #"^\"|\"$" ""))
           (remove str/blank?)
           vec)))

(def page-self
  "landing page が自分について言っていること（`(def default-db {...})`）。
  移行前は svelte の `const app = {...};`（JSON-shaped の JS object リテラル）
  を JSON.parse で読んでいた。cljs 版は同じ内容を EDN map リテラルとして持つ
  ので、JSON.parse ではなく EDN reader（`cljs.reader/read-string`）で読み、
  キーは（JS 文字列キーではなく）keyword になる — :route-count / :xrpc? /
  :relative-path 等、page.cljs 側の語彙と同じ。default-db は入れ子の map を
  持たない（値は文字列・数値・真偽値・文字列 vector のみ）ので、非貪欲な
  `\\{.*?\\}` で安全に囲みを取れる。"
  (-> (extract-1 page #"(?s)\(def default-db\s*(\{.*?\})\)" page-path "default-db")
      reader/read-string))

;; ─── actor identity ────────────────────────────────────────────────────

(deftest actor-identity-agrees-across-surfaces
  (testing "DID は thin edge と identity 文書で同じ"
    (is (= actor-did (get kotodama "@id"))))

  (testing "did:web の host が実際に配備される route である"
    ;; did:web は host からの解決なので、名乗る host に配備が無ければ
    ;; 解決した相手と喋る相手が別人になる。
    (let [host (str/replace actor-did #"^did:web:" "")
          patterns (set (map #(get % "pattern") (get wrangler "routes")))]
      (is (contains? patterns (str host "/*"))
          (str "route が無い host を DID が名乗っている: " host))))

  (testing "route の zone は全て etzhayyim.com"
    (is (= #{"etzhayyim.com"} (set (map #(get % "zone_name") (get wrangler "routes")))))))

(deftest nanoid-agrees-across-five-surfaces
  ;; nanoid は worker 名・route・var・identity 文書・edge の fallback の 5 面に
  ;; 現れる。1 面だけ動くと、配備先の逆引きが切れる。
  (let [n (get kotodama "nanoid")]
    (testing "identity 文書の nanoid が var と一致する"
      (is (= n (get wrangler-vars "APP_NANOID"))))
    (testing "worker 名が nanoid から導出されている"
      (is (= (str "kotodama-" n) (get wrangler "name"))))
    (testing "nanoid の host が route にある"
      (is (contains? (set (map #(get % "pattern") (get wrangler "routes")))
                     (str n ".etzhayyim.com/*"))))
    (testing "thin edge の fallback nanoid が同じ値"
      ;; var が消えた配備でも同じ actor を名乗るための既定値。
      (is (= n (extract-1 edge #"env\.APP_NANOID \?\? \"([^\"]+)\"" edge-path "nanoid fallback"))))))

;; ─── 能力表 ────────────────────────────────────────────────────────────

(deftest capabilities-agree-across-three-surfaces
  (let [declared (js->clj (js/JSON.parse (get wrangler-vars "APP_CAPABILITIES")))]
    (testing "wrangler の APP_CAPABILITIES と identity 文書の capabilities が同順で一致"
      (is (= declared (get kotodama-profile "capabilities"))))
    (testing "thin edge の health probe が名乗る methods も同じ"
      (is (= declared edge-methods)))
    (testing "4 つある"
      (is (= 4 (count declared))))))

;; ─── lexicon ───────────────────────────────────────────────────────────

(deftest lexicon-namespace-agrees-with-the-subscription
  (testing "NSID prefix が購読する collection の親である"
    (let [collections (get-in kotodama ["triggers" "subscribeRepos" "collections"])]
      (is (seq collections) "購読 collection が 1 つも無い")
      (doseq [c collections]
        (is (str/starts-with? c nsid-prefix)
            (str "collection が NSID prefix の外に居る: " c)))))

  (testing "NSID の末尾 segment が project 名から導出されている"
    ;; `yard-ops` → `yardOps`。project の改名で lexicon を忘れると、
    ;; 転送は通るのに購読が空振りする。
    (let [project (get kotodama "project")
          camel (let [[h & t] (str/split project #"-")]
                  (apply str h (map str/capitalize t)))]
      (is (= (str "com.etzhayyim.apps." camel ".") nsid-prefix)))))

;; ─── 保存された旧 XRPC 面（配備されていない） ──────────────────────────

(deftest deployed-surface-is-static-assets-only-no-backend-entrypoint
  (testing "wrangler は main を持たない（静的 assets だけを配信する）"
    (is (not (contains? (set (keys wrangler)) "main"))))
  (testing "assets.directory が cljs のビルド出力を指す"
    (is (= "./cljs/public" (get-in wrangler ["assets" "directory"]))))
  (testing "src/app.ts は配備 config のどこからも参照されていない"
    (is (not (str/includes? (slurp-file wrangler-path) "src/app.ts"))))
  (testing "保存された旧 XRPC 面も配備 config から参照されていない（配線されていない）"
    (is (not (str/includes? (slurp-file wrangler-path) "xrpc-agentgateway-proxy"))))
  (testing "framework var は新しい frontend framework を名乗り、保存された旧 backend が押す BFF header とはもう一致しない"
    ;; 移行前はこの2つが同じ値（sveltekit-edge-bff）で、SvelteKit が両方を
    ;; サーブしていたことの証拠だった。移行後は frontend framework だけが
    ;; 変わり、保存された（配線されていない）backend の自己申告はそのまま
    ;; body 無改造で残る —— 一致しないことが正しい新しい現実である。
    (let [bff-header (extract-1 deployed #"'x-etzhayyim-bff',\s*'([^']+)'" deployed-path "bff header")]
      (is (= "cljs-reagent-re-frame-jp-go-dds" (get wrangler-vars "APP_FRAMEWORK")))
      (is (= "sveltekit-edge-bff" bff-header))
      (is (not= (get wrangler-vars "APP_FRAMEWORK") bff-header)
          "framework var と保存された BFF header が一致してしまった —— どちらかが黙って揃えられた"))))

(deftest preserved-backend-file-carries-its-provenance-marker
  (testing "先頭行が保存マーカーである"
    (is (str/starts-with? deployed "// SVELTEKIT-BACKEND-PRESERVED: moved out of svelte/ during the cljs migration; not wired."))))

(deftest deployed-xrpc-face-delegates-trust-and-has-no-nsid-allowlist
  ;; この deftest 名は移行前からの継続 —— 中身（trust 委譲・allowlist 不在・
  ;; method 面）は body 無改造で保存されているので、これらの assertion は
  ;; 全部そのまま成立する。ただし今はもう「配備される面」ではない
  ;; （上の deployed-surface-is-static-assets-only-no-backend-entrypoint を見よ）。
  (testing "POST と OPTIONS だけを export し、GET は無い"
    ;; thin edge は GET を受けるので、2 面は method 面で食い違う。
    (is (re-find #"export const POST" deployed))
    (is (re-find #"export const OPTIONS" deployed))
    (is (nil? (re-find #"export const GET" deployed))
        "GET が生えたなら thin edge との差が消えた。表を更新すること"))

  (testing "NSID の allowlist を持たない（任意の path を転送する）"
    (is (not (str/includes? deployed nsid-prefix))
        "配備面が prefix を検査し始めたなら、この repo の信頼モデルが変わった"))

  (testing "呼び手の header をそのまま上流へ委譲する（host だけ落とす）"
    (is (re-find #"new Headers\(event\.request\.headers\)" deployed))
    (is (re-find #"headers\.delete\('host'\)" deployed))
    (is (nil? (re-find #"headers\.delete\('authorization'\)" deployed))
        "authorization を落とし始めたなら、委譲ではなく検査になった"))

  (testing "上流は MCP router であって dispatcher ではない"
    (let [default-url (extract-1 deployed #"DEFAULT_MCP_ROUTER_URL = '([^']+)'" deployed-path "mcp default")]
      (is (= default-url (get wrangler-vars "AGENTGATEWAY_MCP_ROUTER_URL"))
          "既定値と配備 var が別の router を指している")
      (is (str/includes? default-url "mcp.etzhayyim.com"))))

  (testing "読む var 名が wrangler の設定する var 名と一致する"
    (is (re-find #"env\.AGENTGATEWAY_MCP_ROUTER_URL" deployed))
    (is (contains? (set (keys wrangler-vars)) "AGENTGATEWAY_MCP_ROUTER_URL")))

  (testing "JSON-RPC の tools/call として包む"
    (is (re-find #"jsonrpc: '2\.0'" deployed))
    (is (re-find #"method: 'tools/call'" deployed)))

  (testing "OPTIONS は任意 origin を許す"
    ;; 絞ったなら、それは意図した変更なのでここを更新する。
    (is (re-find #"'access-control-allow-origin': '\*'" deployed)))

  (testing "応答は no-store"
    (is (re-find #"headers\.set\('cache-control', 'no-store'\)" deployed))))

;; ─── thin edge の guard（配備されないが、在る） ────────────────────────

(deftest thin-edge-refuses-paths-outside-its-lexicon
  (testing "prefix の外は 404 NotFound"
    (is (re-find #"nsid\.startsWith\(NSID_PREFIX\)" edge))
    (is (re-find #"\{ error: \"NotFound\" \}, 404" edge)))
  (testing "受ける method は POST と GET だけ"
    (is (re-find #"req\.method === \"POST\" \|\| req\.method === \"GET\"" edge)))
  (testing "壊れた JSON は 400 InvalidJson"
    (is (re-find #"body\.__invalidJson" edge))
    (is (re-find #"\{ error: \"InvalidJson\" \}, 400" edge)))
  (testing "query は body を上書きしない"
    (is (re-find #"if \(!\(k in body\)\) body\[k\] = v;" edge))))

(deftest thin-edge-sends-an-empty-internal-secret-when-unset
  ;; 既知の fail-open。**拒否ではなく空文字を送る**ので、上流が緩ければ
  ;; 秘密無しで通る。塞いだ日にここが赤くなり、表を更新させる。
  (testing "DISPATCHER_INTERNAL_SECRET は wrangler の vars に無い"
    (is (not (contains? (set (keys wrangler-vars)) "DISPATCHER_INTERNAL_SECRET"))))
  (testing "未設定なら空文字にフォールバックする（refuse しない）"
    (is (re-find #"env\.DISPATCHER_INTERNAL_SECRET \?\? \"\"" edge)))
  (testing "その値をそのまま x-internal-secret として送る"
    (is (re-find #"\"x-internal-secret\": secret" edge))))

(deftest known-unset-deploy-vars-are-still-unset
  ;; deref される var のうち wrangler が設定していないもの。値を足した日に
  ;; 赤くなる —— 「未設定であること」と「表が古いこと」を区別するため。
  (let [known-unset #{"DISPATCHER_URL" "DISPATCHER_INTERNAL_SECRET"}
        set-keys (set (keys wrangler-vars))]
    (doseq [v known-unset]
      (is (not (contains? set-keys v))
          (str v " が設定された。known-unset の表から外すこと")))
    (testing "DISPATCHER_URL には無防備な既定値がある"
      (is (re-find #"env\.DISPATCHER_URL \?\? \"https://dispatcher\.etzhayyim\.com\"" edge)))))

;; ─── health probe ──────────────────────────────────────────────────────

(deftest health-probe-answers-on-both-documented-paths
  (testing "/health と /_app/meta の両方"
    (is (re-find #"url\.pathname === \"/health\"" edge))
    (is (re-find #"url\.pathname === \"/_app/meta\"" edge)))
  (testing "execution 文字列が上流 2 段（MCP + LangServer）を名乗る"
    (let [execution (extract-1 edge #"execution: \"([^\"]+)\"" edge-path "execution")]
      (is (str/includes? execution "agentgateway-mcp"))
      (is (str/includes? execution "langserver"))))
  (testing "bpmn の path が抽出元 repo を指す"
    (let [bpmn (extract-1 edge #"bpmn: \"([^\"]+)\"" edge-path "bpmn")]
      (is (str/starts-with? bpmn "etzhayyim-root/"))
      (is (str/ends-with? bpmn (get kotodama "project"))))))

;; ─── 名前と記述 ────────────────────────────────────────────────────────

(deftest project-names-agree-across-surfaces
  (let [name* (get pkg "name")]
    (testing "cljs の package は同じ名前に -cljs を足したもの"
      (is (= (str name* "-cljs") (get cljs-pkg "name"))))
    (testing "抽出元 path の leaf が package 名"
      (is (= name* (last (str/split (get-in migration [:source :path]) #"/")))))
    (testing "README の名乗りが移設先 repo の leaf"
      (is (= (:name readme) (last (str/split (get-in migration [:destination :repository]) #"/")))))
    (testing "landing page の自己記述が package 名と一致する"
      (is (= name* (:name page-self)))
      (is (= name* (:project page-self))))
    (testing "landing page が名乗る source path は自分自身への repo-relative path である"
      ;; wave 1（svelte 時代）は relativePath が抽出元の絶対パス
      ;; （60-apps/etzhayyim-project-yard-ops/svelte/...）を自己記述として
      ;; 持っていた。wave 2（cljs）はもう抽出直後の repo ではないので、
      ;; :relative-path は「自分の実際のファイルパス」を指す（app-tia の
      ;; 前例と同じ形）。抽出元の provenance は relative-path ではなく
      ;; ns docstring に VERBATIM で残す（下のテストで pin する）。
      (is (= "cljs/src/yard_ops/app.cljs" (:relative-path page-self))))
    (testing "抽出元 svelte path が app.cljs の docstring に VERBATIM で残っている"
      ;; :relative-path がもう抽出元を指さなくなった代わりに、provenance を
      ;; 落とさないための pin。
      (is (str/includes? page "60-apps/etzhayyim-project-yard-ops/svelte/src/routes/+page.svelte")))))

(deftest display-metadata-agrees-between-wrangler-and-identity
  (testing "表示名"
    (is (= (get wrangler-vars "APP_DISPLAY_NAME") (get kotodama-profile "displayName"))))
  (testing "UI 種別"
    (is (= (get wrangler-vars "APP_UI_TYPE") (get kotodama "uiType"))))
  (testing "performer 種別"
    (is (= (get wrangler-vars "APP_PERFORMER_TYPE") (get kotodama "performerType"))))
  (testing "説明は前半だけ一致する（後半は面ごとに違う）"
    ;; wrangler は robot mission を、identity 文書は cost-compression を続ける。
    ;; **完全一致を主張しない** —— 事実でないことを pin すると、直す側が
    ;; 正しい変更を退行として読む。
    (let [shared "yard-ops — gate-in / dock-door / gate-out coordination"]
      (is (str/starts-with? (get wrangler-vars "APP_DESCRIPTION") shared))
      (is (str/starts-with? (get kotodama-profile "description") shared)))))

;; ─── 配備される landing page は今、実際の route/var を出している（直った） ──

(deftest landing-page-summary-now-reflects-real-routes-and-vars
  ;; 旧テスト `known-empty-landing-page-summary-is-still-empty` は、配備される
  ;; landing page が routeCount 0 / routes [] / vars [] という stale な空
  ;; literal を表示し続けていることを pin していた。その docstring 自身が
  ;; 「直った日にここが赤くなる —— 直ったのに記録が古いままなのを区別する
  ;; ため」と書いていたとおり、この cljs 移行で実際に直った
  ;; （:routes / :vars を wrangler.jsonc から起こした）ので、ここでその
  ;; 『直った』状態を新しく pin する。
  (testing "wrangler は実際に route と var を持っている"
    (is (= 2 (count (get wrangler "routes"))))
    (is (<= 8 (count wrangler-vars))))
  (testing "landing page の route-count が wrangler の route 数と一致する"
    (is (= (count (get wrangler "routes")) (:route-count page-self))))
  (testing "landing page の routes が wrangler の route pattern と一致する（順序込み）"
    (is (= (mapv #(get % "pattern") (get wrangler "routes")) (:routes page-self))))
  (testing "landing page の vars が wrangler の vars map の全キーを漏らさず含む"
    (is (= (set (keys wrangler-vars)) (set (:vars page-self))))
    (is (= (count wrangler-vars) (count (:vars page-self)))))
  (testing "xrpc は enabled と表示され、保存された旧 XRPC 面（配線されていないが）実在する"
    (is (true? (:xrpc? page-self)))
    (is (fs/existsSync deployed-path))))

;; ─── wave 2（svelte→cljs）が足したファイル ──────────────────────────

(def wave-2-migration-additions
  "svelte→cljs 移行（2026-09-07、wave 2）がこの commit で足したファイルの
  prefix 宣言。migration.edn は wave 1（etzhayyim/root からの最初の抽出）
  だけを記述する別の provenance イベントなので書き換えない —— 書き換えると
  :source の revision/tree/bytes が指す実際の抽出内容とずれる。ここは
  wave 1 の allowed-additions と同じ役割を、この test ファイル自身が
  wave 2 について宣言する場所。"
  #{"cljs" "src/xrpc-agentgateway-proxy.ts"})

(defn- wave-2-addition? [f]
  (some #(or (= f %) (str/starts-with? f (str % "/"))) wave-2-migration-additions))

(defn- head-originals
  "HEAD のファイルのうち、宣言された追加物（wave 1: migration.edn の
  :identity :allowed-additions、wave 2: この test ファイルの
  wave-2-migration-additions）でないもの = wave-1 抽出物のうちまだ
  残っているもの。svelte→cljs 移行で svelte/ の 7 ファイルが消えたので、
  wave-1 originals は 12 から 5 に減った。"
  []
  (let [wave1-additions (set (get-in migration [:identity :allowed-additions]))
        wave1-added? (fn [f] (some #(or (= f %) (str/starts-with? f (str % "/"))) wave1-additions))]
    (remove #(or (wave1-added? %) (wave-2-addition? %)) (head-files))))

(deftest migration-file-set-still-matches-the-extraction
  ;; migration.edn は wave 1（etzhayyim/root からの抽出、12 ファイル）を
  ;; 記述する。svelte→cljs 移行（wave 2）は元の抽出のうち svelte/ の
  ;; 7 ファイルを削除したので、残る wave-1 originals は 12 - 7 = 5
  ;; （NOTICE / kotodama.jsonld / package.json / wrangler.jsonc / src/app.ts）。
  (let [originals (head-originals)]
    (testing "wave-2 で svelte/ の 7 ファイルが削除され、残る wave-1 originals は 5"
      (is (= 5 (count originals))
          (str "抽出物のうち残っているもの: " (pr-str (vec (sort originals))))))
    (testing "宣言された wave-1 追加物は全て実在する（消えた宣言を残さない）"
      (doseq [a (get-in migration [:identity :allowed-additions])]
        (is (fs/existsSync a) (str "宣言された追加物が無い: " a))))
    (testing "宣言された wave-2 追加物も全て実在する"
      (doseq [a (sort wave-2-migration-additions)]
        (is (fs/existsSync a) (str "宣言された wave-2 追加物が無い: " a))))
    (testing "抽出元の revision と tree が 40 桁の sha で固定されている"
      (is (re-matches #"[0-9a-f]{40}" (get-in migration [:source :revision])))
      (is (re-matches #"[0-9a-f]{40}" (get-in migration [:source :tree]))))))

(deftest migration-byte-total-still-matches-the-extraction
  ;; wave 2 で svelte/ が削除され、残る 5 originals のうち wrangler.jsonc の
  ;; 内容も変わった（main 除去・framework var・assets.directory）ので、元の
  ;; :bytes（13299、wave-1 の 12 originals 全部）はもう成立しない。ここでは
  ;; 残る 5 originals の、この移行 commit 時点での合計バイト数を pin する。
  (testing "残る wave-1 originals の合計バイト数"
    (is (= 6402 (reduce + 0 (map head-size (head-originals)))))))

(deftest repository-declares-itself-as-an-edn-first-app
  (testing "README.edn が kind と canonical-metadata を宣言する"
    (is (= :app (:kind readme)))
    (is (= :edn (:canonical-metadata readme))))
  (testing "canonical-metadata が :edn なので README.md を置かない"
    ;; .md を足したら、名乗りが 2 箇所になり、どちらが正かが出力から消える。
    (is (not (fs/existsSync "README.md"))))
  (testing "NOTICE が在る"
    (is (fs/existsSync "NOTICE"))))
