;; SPDX-FileCopyrightText: The kajero Authors
;; SPDX-License-Identifier: AGPL-3.0-or-later
(ns kajero.web
  "The pages for humans, rendered on the server: saved pages, ad-hoc views of
   any selector, entries with their standing and history, the labels in use,
   and forms to write, revise and observe.

   The server listens on 127.0.0.1 only and writes as KAJERO_ACTOR, signed
   with KAJERO_KEY, so an edit made in the browser is a record of the local
   human like any other. Every form carries a token minted when the server
   starts, and a request a browser marks as cross-site is refused, so another
   web page can not write into the notebook through the visitor's browser."
  (:require [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [kajero.core :as k]
            [kajero.data :as data]
            [kajero.mcp :as mcp])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util HexFormat)
           (org.commonmark.ext.gfm.tables TablesExtension)
           (org.commonmark.parser Parser)
           (org.commonmark.renderer.html HtmlRenderer)))

;; --------------------------------------------------------------- markdown

(def ^:private extensions [(TablesExtension/create)])
(def ^:private ^Parser parser (.build (.extensions (Parser/builder) extensions)))
(def ^:private ^HtmlRenderer renderer
  (-> (HtmlRenderer/builder) (.extensions extensions) (.escapeHtml true) (.sanitizeUrls true) .build))

(defn markdown
  "Markdown as HTML; raw HTML in the source is escaped, unsafe URLs dropped."
  [s]
  (h/raw (.render renderer (.parse parser (or s "")))))

;; ------------------------------------------------------------------ style

(def ^:private css "
:root{--paper:#f5f0e6;--card:#fffdf8;--ink:#2a2723;--muted:#6f675c;--rule:#e4dccd;
--margin:#d9534f33;--accent:#2f5d8a;--accent-ink:#fff;--held:#2e7d4f;--failed:#b3261e;--stale:#a26a00;
--chip:#efe7d8;--shadow:0 1px 0 #0000000d,0 8px 24px -16px #0000002e;color-scheme:light}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--paper:#121418;--card:#1a1d23;--ink:#e9e4da;
--muted:#9a9387;--rule:#2b2f37;--margin:#e0605a40;--accent:#8fb8e8;--accent-ink:#0d1117;--held:#6cc58f;
--failed:#ff8a80;--stale:#e3b45a;--chip:#252a33;--shadow:0 1px 0 #ffffff08,0 12px 28px -18px #000000cc;color-scheme:dark}}
:root[data-theme=dark]{--paper:#121418;--card:#1a1d23;--ink:#e9e4da;--muted:#9a9387;--rule:#2b2f37;
--margin:#e0605a40;--accent:#8fb8e8;--accent-ink:#0d1117;--held:#6cc58f;--failed:#ff8a80;--stale:#e3b45a;
--chip:#252a33;--shadow:0 1px 0 #ffffff08,0 12px 28px -18px #000000cc;color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;background:var(--paper);color:var(--ink);
font:16px/1.6 'Iowan Old Style','Charter','Palatino Linotype',ui-serif,Georgia,serif}
a{color:var(--accent);text-decoration-thickness:1px;text-underline-offset:3px}
code,pre,.mono,input,textarea,select,button{font-family:ui-monospace,'SF Mono','JetBrains Mono',Menlo,monospace}
code{font-size:.88em;background:var(--chip);padding:.1em .35em;border-radius:4px}
pre{background:var(--chip);padding:1rem;border-radius:8px;overflow-x:auto;font-size:.85rem}
pre code{background:none;padding:0}
header.top{position:sticky;top:0;z-index:5;backdrop-filter:blur(10px);
background:color-mix(in srgb,var(--paper) 82%,transparent);border-bottom:1px solid var(--rule)}
.bar{max-width:1080px;margin:0 auto;padding:.7rem 1rem;display:flex;gap:1rem;align-items:center;flex-wrap:wrap}
.brand{font-size:1.45rem;font-weight:700;letter-spacing:.01em;color:var(--ink);text-decoration:none;
display:flex;align-items:center;gap:.45rem}
.brand svg{width:26px;height:26px}
nav{display:flex;gap:.9rem;font-size:.95rem}
nav a{color:var(--muted);text-decoration:none}nav a:hover{color:var(--ink)}
form.search{flex:1;min-width:220px;display:flex}
form.search input{width:100%;padding:.5rem .75rem;border:1px solid var(--rule);border-radius:8px;
background:var(--card);color:var(--ink);font-size:.85rem}
button.theme{border:1px solid var(--rule);background:var(--card);color:var(--ink);border-radius:8px;
padding:.35rem .5rem;cursor:pointer;line-height:0}
button.theme svg{width:18px;height:18px}
main{max-width:1080px;margin:0 auto;padding:1.5rem 1rem 4rem}
h1{font-size:2.1rem;line-height:1.15;margin:.2rem 0 .6rem;font-weight:700}
h2{font-size:1.3rem;margin:2rem 0 .7rem}
.lead{color:var(--muted);margin-top:0}
.grid{display:grid;gap:1rem;grid-template-columns:repeat(auto-fill,minmax(260px,1fr))}
.card{background:var(--card);border:1px solid var(--rule);border-radius:12px;padding:1rem 1.1rem;
box-shadow:var(--shadow);position:relative}
.sheet{background:var(--card);border:1px solid var(--rule);border-radius:14px;box-shadow:var(--shadow);
padding:1.4rem 1.6rem 1.4rem 2.6rem;position:relative;
background-image:linear-gradient(90deg,transparent 1.55rem,var(--margin) 1.55rem,var(--margin) calc(1.55rem + 2px),transparent calc(1.55rem + 2px))}
.sheet+.sheet{margin-top:1.1rem}
.sheet h3{margin:0 0 .3rem;font-size:1.25rem}
.sheet h3 a{color:var(--ink);text-decoration:none}.sheet h3 a:hover{text-decoration:underline}
.body>:first-child{margin-top:.4rem}.body table{border-collapse:collapse;margin:.6rem 0}
.body th,.body td{border:1px solid var(--rule);padding:.3rem .6rem}
.chips{display:flex;flex-wrap:wrap;gap:.35rem;margin:.3rem 0}
.chip{font:12px/1.2 ui-monospace,Menlo,monospace;background:var(--chip);color:var(--ink);
padding:.25rem .5rem;border-radius:999px;text-decoration:none;border:1px solid transparent}
.chip:hover{border-color:var(--rule)}.chip b{color:var(--muted);font-weight:400}
.meta{color:var(--muted);font-size:.85rem}
.standing{display:flex;gap:.5rem;flex-wrap:wrap;margin:.5rem 0}
.badge{font:12px/1 ui-monospace,Menlo,monospace;padding:.3rem .55rem;border-radius:6px;
border:1px solid var(--rule);display:inline-flex;gap:.35rem;align-items:center}
.badge.held{color:var(--held)}.badge.failed,.badge.disputed{color:var(--failed);border-color:color-mix(in srgb,var(--failed) 45%,transparent)}
.badge.stale{color:var(--stale);border-color:color-mix(in srgb,var(--stale) 45%,transparent)}
.badge.unsigned{color:var(--muted)}
.tally{letter-spacing:.08em}
.group{margin-top:2rem}.group>h2{font-family:ui-monospace,Menlo,monospace;font-size:.9rem;
text-transform:uppercase;letter-spacing:.12em;color:var(--muted);border-bottom:1px solid var(--rule);padding-bottom:.4rem}
.row{display:flex;gap:1rem;flex-wrap:wrap;align-items:flex-start}
.row>*{flex:1;min-width:280px}
ol.timeline{list-style:none;padding:0;margin:0;border-left:2px solid var(--rule)}
ol.timeline li{padding:.2rem 0 .9rem 1rem;position:relative;font-size:.92rem}
ol.timeline li:before{content:'';position:absolute;left:-7px;top:.55rem;width:12px;height:12px;border-radius:50%;
background:var(--card);border:2px solid var(--muted)}
ol.timeline li.observation-held:before{border-color:var(--held)}
ol.timeline li.observation-failed:before{border-color:var(--failed)}
ol.timeline li.revision:before{border-color:var(--accent)}
form.stack{display:grid;gap:.7rem}
label.f{display:grid;gap:.25rem;font-size:.85rem;color:var(--muted)}
.f input,.f textarea,.f select{padding:.55rem .7rem;border:1px solid var(--rule);border-radius:8px;
background:var(--paper);color:var(--ink);font-size:.9rem}
.f textarea{min-height:12rem;line-height:1.5}
button.go{background:var(--accent);color:var(--accent-ink);border:0;border-radius:8px;padding:.55rem 1rem;
font-weight:600;cursor:pointer;justify-self:start}
button.go.failed{background:var(--failed)}button.go.held{background:var(--held)}
details>summary{cursor:pointer;color:var(--accent)}
.notice{border:1px solid color-mix(in srgb,var(--failed) 45%,transparent);color:var(--failed);
background:color-mix(in srgb,var(--failed) 8%,transparent);padding:.8rem 1rem;border-radius:10px}
.empty{color:var(--muted);font-style:italic}
.selector{font:13px ui-monospace,Menlo,monospace;color:var(--muted)}
.data{margin:.8rem 0;overflow-x:auto}
table.grid-data{border-collapse:collapse;width:100%;font:13px/1.45 ui-monospace,Menlo,monospace}
table.grid-data th{text-align:left;position:sticky;top:0;background:var(--chip);cursor:pointer;
user-select:none;white-space:nowrap}
table.grid-data th:after{content:' ↕';color:var(--muted);font-size:.8em}
table.grid-data th,table.grid-data td{border-bottom:1px solid var(--rule);padding:.4rem .7rem}
table.grid-data tr:hover td{background:color-mix(in srgb,var(--accent) 7%,transparent)}
table.grid-data td.num{text-align:right;font-variant-numeric:tabular-nums}
dl.data{display:grid;grid-template-columns:max-content 1fr;gap:.3rem 1rem;font-size:.92rem}
dl.data dt{font-family:ui-monospace,Menlo,monospace;color:var(--muted)}dl.data dd{margin:0}
.ticket{display:inline-flex;gap:.4rem;align-items:center;font:12px/1.2 ui-monospace,Menlo,monospace;
border:1px solid var(--rule);border-radius:6px;padding:.3rem .55rem;margin:.15rem .3rem .15rem 0}
.ticket .stage{color:var(--accent)}
.schema-note{font-size:.8rem;color:var(--muted)}
@media (max-width:640px){h1{font-size:1.7rem}.sheet{padding:1.1rem 1rem 1.1rem 2.2rem}}
")

(def ^:private theme-script
  "(function(){var k='kajero-theme',r=document.documentElement;
try{var t=localStorage.getItem(k);if(t)r.dataset.theme=t}catch(e){}
window.kajeroTheme=function(){var d=r.dataset.theme||(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');
var n=d==='dark'?'light':'dark';r.dataset.theme=n;try{localStorage.setItem(k,n)}catch(e){}};
document.addEventListener('click',function(ev){var th=ev.target.closest('table.grid-data th');if(!th)return;
var t=th.closest('table'),i=[].indexOf.call(th.parentNode.children,th),b=t.tBodies[0],
asc=th.dataset.sort!=='asc';[].forEach.call(th.parentNode.children,function(c){delete c.dataset.sort});
th.dataset.sort=asc?'asc':'desc';var rows=[].slice.call(b.rows);rows.sort(function(x,y){
var a=x.cells[i].dataset.v,c=y.cells[i].dataset.v,na=parseFloat(a),nc=parseFloat(c);
var r=(!isNaN(na)&&!isNaN(nc))?na-nc:a.localeCompare(c);return asc?r:-r});rows.forEach(function(r){b.appendChild(r)})})})();")

(def ^:private logo
  [:svg {:viewBox "0 0 32 32" :fill "none" :stroke "currentColor" :stroke-width "2" :aria-hidden "true"}
   [:rect {:x "6" :y "3" :width "20" :height "26" :rx "3"}]
   [:path {:d "M11 3v26M15 10h7M15 15h7M15 20h4"}]])

(def ^:private theme-icon
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2" :aria-hidden "true"}
   [:path {:d "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"}]])

;; ---------------------------------------------------------------- helpers

(defn- enc [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- view-href [selector & [group-by]]
  (str "/view?selector=" (enc selector) (when group-by (str "&group-by=" (enc group-by)))))

(defn- when-str [at] (some-> at (subs 0 (min 16 (count at))) (str/replace "T" " ")))

(defn- chips [labels]
  [:div.chips
   (for [[key value] (sort labels)]
     [:a.chip {:href (view-href (str "label:" key "=" value))} [:b key "="] value])])

(defn- tally [n]
  (str (str/join (repeat (quot n 5) "𝍸 ")) (str/join (repeat (mod n 5) "|"))))

(defn- standing [{:keys [held failed last-held disputed? stale?]} signed?]
  [:div.standing
   [:span.badge.held {:title (str "independent held observations" (when last-held (str ", last " (when-str last-held))))}
    "held " [:span.tally (if (pos? held) (tally held) "0")]]
   (when (pos? failed) [:span.badge.failed "failed " failed])
   (when disputed? [:span.badge.disputed "disputed"])
   (when stale? [:span.badge.stale "stale"])
   (when (false? signed?) [:span.badge.unsigned "unsigned"])])

(defn- layout [{:keys [title selector]} & content]
  (str "<!doctype html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title (if title (str title " · kajero") "kajero")]
          [:style (h/raw css)]
          [:script (h/raw theme-script)]]
         [:body
          [:header.top
           [:div.bar
            [:a.brand {:href "/"} logo "kajero"]
            [:form.search {:action "/view" :method "get" :role "search"}
             [:input {:name "selector" :value (or selector "") :aria-label "Selector"
                      :placeholder "label:system=calico not disputed ~apiservices"}]]
            [:nav [:a {:href "/"} "Pages"] [:a {:href "/labels"} "Labels"] [:a {:href "/new"} "New entry"]]
            [:button.theme {:type "button" :onclick "kajeroTheme()" :aria-label "Switch between light and dark"} theme-icon]]]
          [:main content]]])))

(defn- cell-text [v]
  (cond (keyword? v) (name v) (string? v) v (nil? v) "" :else (pr-str v)))

(defn- pretty [x] (with-out-str (pp/pprint x)))

(defn- schema-form [nb {:keys [schema]}]
  (when schema (:data (k/current nb (:entry schema)))))

(defn- data-view
  "The revision's data for a human: a sortable table for a list of maps, a
   definition list for a map, a schema as EDN, anything else pretty-printed."
  [nb {:keys [data labels schema used-by outdated-schema?] :as e}]
  (when (contains? e :data)
    [:div.data
     (cond
       (= "schema" (get labels "kind"))
       (list [:p.schema-note "malli schema"] [:pre [:code (pretty data)]]
             (when (seq used-by)
               [:p.schema-note (str "used by " (count used-by) " "
                                    (if (= 1 (count used-by)) "entry" "entries") ": ")
                (interpose ", " (for [{:keys [entry title current?]} used-by]
                                  [:span [:a {:href (str "/e/" entry)} title]
                                   (when-not current? " (older revision)")]))]))

       (= "migration" (get labels "kind"))
       (list [:p.schema-note "migration from "
              [:a {:href (str "/e/" (:from data))} (or (:title (k/current nb (:from data))) (:from data))]
              " to "
              [:a {:href (str "/e/" (:to data))} (or (:title (k/current nb (:to data))) (:to data))]]
             [:pre [:code (pretty (:steps data))]])

       (data/table? data)
       (let [cols (data/columns data (schema-form nb e))]
         [:table.grid-data
          [:thead [:tr (for [c cols] [:th (cell-text c)])]]
          [:tbody (for [row data]
                    [:tr (for [c cols :let [v (get row c)]]
                           [:td {:class (when (number? v) "num") :data-v (cell-text v)} (cell-text v)])])]])

       (map? data)
       [:dl.data (for [[k v] data] (list [:dt (cell-text k)] [:dd (cell-text v)]))]

       :else [:pre [:code (pretty data)]])
     (when schema
       [:p.schema-note "fits schema " [:a {:href (str "/e/" (:entry schema))}
                                       (or (:title (k/current nb (:entry schema))) (:entry schema))]
        (when outdated-schema? " in an older revision; the schema changed since")])]))

(defn- ticket-chips [tickets]
  (when (seq tickets)
    [:div {:style "margin:.4rem 0"}
     (for [{:keys [ticket title current]} tickets]
       [:a.ticket {:href (view-href (str "ticket=" ticket)) :title (str "entries citing tik ticket " ticket)}
        [:span "tik " (subs ticket 0 8)]
        (when title [:span title])
        (when (seq current) [:span.stage (str/join " " current)])])]))

(defn- sheet [nb {:keys [entry title body labels at actor standing signed?] :as e} full?]
  [:article.sheet
   [:h3 [:a {:href (str "/e/" entry)} title]]
   (chips labels)
   (kajero.web/standing standing (if (contains? e :signed?) signed? true))
   (if full?
     (list [:div.body (markdown body)] (data-view nb e))
     (when-let [s (:summary e)] [:p.meta s]))
   [:div.meta (when actor (str actor " · ")) (when-str at)]])

(defn- grouped [entries group-by render]
  (if (str/blank? group-by)
    (map render entries)
    (for [[value es] (sort-by #(or (key %) "~") (clojure.core/group-by #(get-in % [:labels group-by]) entries))]
      [:section.group [:h2 group-by " = " (or value "(none)")] (map render es)])))

;; ------------------------------------------------------------------ pages

(defn- home [nb]
  (layout {}
          [:h1 "Notebook"]
          [:p.lead "Pages are saved selectors; every entry appears wherever a selector matches it."]
          [:h2 "Pages"]
          (if-let [ps (seq (k/pages nb))]
            [:div.grid (for [{:keys [name title selector intro]} ps]
                         [:a.card {:href (str "/p/" name) :style "text-decoration:none;color:inherit"}
                          [:strong title] [:div.selector selector]
                          (when-not (str/blank? intro) [:p.meta (first (str/split-lines intro))])])]
            [:p.empty "No pages yet. Search for a selector and save the view as a page."])
          [:h2 "Recently changed"]
          (let [recent (take 12 (k/search nb ""))]
            (if (seq recent) (map #(sheet nb % false) recent) [:p.empty "The notebook is empty."]))))

(defn- full-entries [nb selector]
  (map #(k/entry nb (:entry %)) (k/search nb selector)))

(defn- view [nb {:strs [selector group-by]} token]
  (let [entries (full-entries nb selector)]
    (layout {:title (if (str/blank? selector) "Every entry" selector) :selector selector}
            [:h1 (if (str/blank? selector) "Every entry" "View")]
            [:p.selector (or (not-empty selector) "(every entry)") " · " (count entries) " entries"]
            (if (seq entries)
              (grouped entries group-by #(sheet nb % true))
              [:p.empty "Nothing matches."])
            [:details {:style "margin-top:2rem"}
             [:summary "Save this view as a page"]
             [:form.stack {:method "post" :action "/pages" :style "margin-top:.8rem;max-width:520px"}
              [:input {:type "hidden" :name "token" :value token}]
              [:input {:type "hidden" :name "selector" :value (or selector "")}]
              [:label.f "Name (lower-case, dashes)" [:input {:name "name" :required true :pattern "[a-z0-9][a-z0-9-]*"}]]
              [:label.f "Title" [:input {:name "title"}]]
              [:label.f "Group by label key" [:input {:name "group-by" :value (or group-by "") :placeholder "kind"}]]
              [:label.f "Intro (Markdown)" [:textarea {:name "intro" :style "min-height:5rem"}]]
              [:button.go {:type "submit"} "Save page"]]])))

(defn- saved-page [nb name]
  (when-let [{:keys [title intro selector group-by]} (k/page nb name)]
    (let [entries (full-entries nb (str selector " not superseded"))]
      (layout {:title title :selector selector}
              [:h1 title]
              (when-not (str/blank? intro) [:div.body.lead (markdown intro)])
              [:p.selector [:a {:href (view-href selector group-by)} selector] " · " (count entries) " entries"]
              (if (seq entries) (grouped entries group-by #(sheet nb % true)) [:p.empty "Nothing matches yet."])))))

(defn- history-item [{:keys [kind actor at outcome evidence title type source target hash signed?]}]
  [:li {:class (if (= kind :observation) (str "observation-" outcome) (name kind))}
   [:div [:strong (case kind
                    :revision (str "revised: " title)
                    :observation (str "observed: " outcome)
                    :link (str source " " type " " target))]]
   [:div.meta actor " · " (when-str at) (when-not signed? " · unsigned")]
   (when (seq evidence) [:div.meta "evidence: " (str/join "; " evidence)])
   [:div.selector {:title hash} (subs hash 0 (min 19 (count hash)))]])

(defn- entry-page [nb id token]
  (when-let [{:keys [title body labels standing evidence links revision actor at signed? tickets] :as e} (k/entry nb id)]
    (layout {:title title}
            [:article.sheet
             [:h1 title]
             (chips labels)
             (kajero.web/standing standing signed?)
             [:div.body (markdown body)]
             (data-view nb e)
             (when (seq evidence) [:p.meta "evidence: " (str/join "; " evidence)])
             (ticket-chips tickets)
             [:p.meta actor " · " (when-str at) " · " [:span.selector revision]]
             (when (seq links)
               [:p.meta "links: "
                (interpose ", " (for [{:keys [source type target]} links]
                                  [:span [:a {:href (str "/e/" source)} (if (= source id) "this" "another entry")]
                                   " " type " "
                                   [:a {:href (str "/e/" target)} (if (= target id) "this" "another entry")]]))])]
            [:div.row {:style "margin-top:1.4rem"}
             [:section.card
              [:h2 {:style "margin-top:0"} "Observe"]
              [:p.meta "Record a fresh check of this revision, with what you ran or read."]
              [:form.stack {:method "post" :action (str "/e/" id "/observe")}
               [:input {:type "hidden" :name "token" :value token}]
               [:input {:type "hidden" :name "revision" :value revision}]
               [:label.f "Evidence" [:input {:name "evidence" :placeholder "kubectl get apiservices | grep calico"}]]
               [:div {:style "display:flex;gap:.6rem"}
                [:button.go.held {:type "submit" :name "outcome" :value "held"} "It held"]
                [:button.go.failed {:type "submit" :name "outcome" :value "failed"} "It failed"]]]]
             [:section.card
              [:h2 {:style "margin-top:0"} "History"]
              [:ol.timeline (map history-item (reverse (k/history nb id)))]]]
            [:details.card {:style "margin-top:1rem"}
             [:summary "Revise this entry"]
             [:form.stack {:method "post" :action (str "/e/" id "/revise") :style "margin-top:.8rem"}
              [:input {:type "hidden" :name "token" :value token}]
              [:input {:type "hidden" :name "base" :value revision}]
              [:label.f "Title" [:input {:name "title" :value title}]]
              [:label.f "Labels (key=value, one per line)"
               [:textarea {:name "labels" :style "min-height:5rem"} (str/join "\n" (map #(str/join "=" %) (sort labels)))]]
              [:label.f "Body (Markdown)" [:textarea {:name "body"} body]]
              [:label.f "Evidence" [:input {:name "evidence"}]]
              [:button.go {:type "submit"} "Save revision"]]])))

(defn- labels-page [nb]
  (layout {:title "Labels"}
          [:h1 "Labels"]
          [:p.lead "Every label of a current revision. Reuse these before inventing new ones."]
          (for [[key values] (k/labels nb)]
            [:section.group
             [:h2 [:a {:href (view-href (str "label:" key) key)} key]]
             [:div.chips (for [[value n] (sort-by (comp - val) values)]
                           [:a.chip {:href (view-href (str "label:" key "=" value))} value " " [:b n]])]])))

(defn- new-page [token]
  (layout {:title "New entry"}
          [:h1 "New entry"]
          [:form.stack.card {:method "post" :action "/new" :style "max-width:760px"}
           [:input {:type "hidden" :name "token" :value token}]
           [:label.f "Title" [:input {:name "title" :required true}]]
           [:label.f "Labels (key=value, one per line)" [:textarea {:name "labels" :style "min-height:5rem" :placeholder "kind=gotcha\nsystem=calico"}]]
           [:label.f "Body (Markdown)" [:textarea {:name "body"}]]
           [:label.f "Evidence" [:input {:name "evidence"}]]
           [:button.go {:type "submit"} "Write entry"]]))

(defn- problem [status message]
  {:status status
   :body (layout {:title "Refused"} [:h1 "Refused"] [:div.notice message] [:p [:a {:href "javascript:history.back()"} "Back"]])})

;; ----------------------------------------------------------------- server

(defn- decode [s] (URLDecoder/decode (str s) StandardCharsets/UTF_8))

(defn- params [s]
  (reduce (fn [m pair]
            (let [[k v] (str/split pair #"=" 2)]
              (if (str/blank? k) m (assoc m (decode k) (decode (or v ""))))))
          {}
          (str/split (or s "") #"&")))

(defn- form-params
  "The fields of a form body, or ::malformed when it holds an escape that is
   not one, which no browser sends."
  [s]
  (try (params s) (catch IllegalArgumentException _ ::malformed)))

(defn- label-lines [s]
  (not-empty (into {} (keep #(let [[k v] (map str/trim (str/split % #"=" 2))]
                               (when-not (str/blank? k) [k (or v "")])))
                   (str/split-lines (or s "")))))

(defn- evidence [s] (if (str/blank? s) [] [(str/trim s)]))

(defn- redirect [to] {:status 303 :headers {"Location" to}})

(defn- post [nb writer token path form]
  (when-not (= token (get form "token"))
    (throw (ex-info "the form is not from this server; reload the page" {:reason :kajero/bad-token})))
  (let [[_ id action] (re-matches #"/e/([^/]+)/(revise|observe)" path)]
    (cond
      (= path "/new")
      (let [{:keys [entry]} (k/write! nb (merge writer {:title (get form "title") :body (get form "body")
                                                        :labels (label-lines (get form "labels"))
                                                        :evidence (evidence (get form "evidence"))}))]
        (redirect (str "/e/" entry)))

      (= path "/pages")
      (let [{:keys [page]} (k/save-page! nb (merge writer {:name (get form "name") :title (get form "title")
                                                           :intro (get form "intro") :selector (get form "selector")
                                                           :group-by (get form "group-by")}))]
        (redirect (str "/p/" page)))

      (= action "revise")
      (do (k/revise! nb id (merge writer {:base (get form "base") :title (get form "title") :body (get form "body")
                                          :labels (label-lines (get form "labels"))
                                          :evidence (evidence (get form "evidence"))}))
          (redirect (str "/e/" (enc id))))

      (= action "observe")
      (do (k/observe! nb id (merge writer {:outcome (get form "outcome") :revision (get form "revision")
                                           :evidence (evidence (get form "evidence"))}))
          (redirect (str "/e/" (enc id))))

      :else nil)))

(defn- edn-response [status value]
  {:status status
   :headers {"Content-Type" "application/edn; charset=utf-8"}
   :body (binding [*print-namespace-maps* false] (pr-str value))})

(defn- edn-read
  "The EDN a tool reads instead of a page: the same data the pages render,
   for probes and scripts. nil when the path names none."
  [nb path q]
  (let [[_ id] (re-matches #"/e/([^/]+)\.edn" path)
        [_ hid] (re-matches #"/e/([^/]+)/history\.edn" path)]
    (try
      (some->> (cond
                 (= path "/view.edn") (k/search nb (get q "selector" ""))
                 (= path "/labels.edn") (k/labels nb)
                 id (k/entry nb (decode id))
                 hid (let [h (k/history nb (decode hid))] (when (seq h) h)))
               (edn-response 200))
      (catch clojure.lang.ExceptionInfo e
        (edn-response 400 {:refused (some-> (:reason (ex-data e)) name) :message (ex-message e)})))))

(defn respond
  "The response to one request: {:status :headers :body}, or nil for 404.
   A path ending in .edn answers with data instead of a page."
  [nb writer token {:keys [method path query form cross-site?]}]
  (try
    (if (= method "POST")
      (if cross-site?
        (problem 403 "a cross-site request can not write into the notebook")
        (post nb writer token path form))
      (let [q (params query)
            [_ id] (re-matches #"/e/([^/.]+)" path)
            [_ page-name] (re-matches #"/p/([a-z0-9-]+)" path)]
        (if (str/ends-with? path ".edn")
          (edn-read nb path q)
          (some->> (cond
                     (= path "/") (home nb)
                     (= path "/view") (view nb q token)
                     (= path "/labels") (labels-page nb)
                     (= path "/new") (new-page token)
                     id (entry-page nb (decode id) token)
                     page-name (saved-page nb page-name))
                   (hash-map :status 200 :body)))))
    (catch clojure.lang.ExceptionInfo e
      (problem 400 (str (ex-message e)
                        (when-let [c (:current (ex-data e))]
                          (str " - someone revised it meanwhile; the current revision is " c)))))))

(defn- own-origins [port]
  #{(str "http://127.0.0.1:" port) (str "http://localhost:" port)})

(defn mcp-endpoint
  "The response to a request on /mcp, MCP's streamable HTTP transport in its
   plain request-response form: one JSON-RPC message per POST, answered with
   JSON, and no server-sent events. nil (404) unless `mcp` names a token.

   Anything on this machine can reach 127.0.0.1, so a request needs the
   bearer token, and one carrying an Origin other than this server's own is
   refused, which keeps a web page from driving it through DNS rebinding.
   Writes are made as the MCP writer, never as the writer of the pages."
  [nb {:keys [token writer port]} {:keys [method origin authorization body]}]
  (when token
    (cond
      (and origin (not ((own-origins port) origin)))
      {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "foreign origin"}

      (not (and authorization
                (MessageDigest/isEqual (.getBytes ^String authorization StandardCharsets/UTF_8)
                                       (.getBytes (str "Bearer " token) StandardCharsets/UTF_8))))
      {:status 401 :headers {"Content-Type" "text/plain; charset=utf-8" "WWW-Authenticate" "Bearer"}
       :body "a bearer token is required"}

      (not= "POST" method)
      {:status 405 :headers {"Content-Type" "text/plain; charset=utf-8" "Allow" "POST"} :body ""}

      :else
      (if-let [response (mcp/handle nb writer body)]
        {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string response)}
        {:status 202 :headers {"Content-Type" "application/json"} :body ""}))))

(defn request-guard
  "A refusal for a request this server must not answer, else nil.

   The server listens on 127.0.0.1, but a web page can still reach it by
   making its own host name resolve to 127.0.0.1 (DNS rebinding); the
   browser then treats the server as that page's origin and sends the page's
   host name. So every request must name this server as its Host, and a
   POST to the pages must carry this server's own Origin, which browsers
   send with every form. A process of the same local user is not stopped by
   this: it can read the signing key itself."
  [port {:keys [host method path origin too-large? form]}]
  (cond
    too-large?
    {:status 413 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "the request body is too large"}

    (= ::malformed form)
    {:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "the form body is not URL-encoded"}

    (not (contains? #{(str "127.0.0.1:" port) (str "localhost:" port)} host))
    {:status 421 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "this server answers only to its own address"}

    (and (= "POST" method) (not= "/mcp" path) (not (contains? (own-origins port) origin)))
    {:status 403 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "a form must come from this server's own pages"}))

(def ^:private max-body
  "The largest request body read, in bytes; a larger one is refused before
   it is parsed."
  (* 16 1024 1024))

(defn- exchange->request [^HttpExchange ex]
  (let [method (.getRequestMethod ex)
        uri (.getRequestURI ex)
        headers (.getRequestHeaders ex)
        raw (when (= "POST" method) (.readNBytes (.getRequestBody ex) (inc max-body)))
        too-large? (and raw (> (alength ^bytes raw) max-body))
        body (when (and raw (not too-large?)) (String. ^bytes raw StandardCharsets/UTF_8))]
    {:method method
     :path (.getRawPath uri)
     :query (.getRawQuery uri)
     :origin (.getFirst headers "Origin")
     :host (.getFirst headers "Host")
     :authorization (.getFirst headers "Authorization")
     :cross-site? (= "cross-site" (.getFirst headers "Sec-Fetch-Site"))
     :too-large? too-large?
     :body body
     ;; Only the pages take form posts; /mcp carries JSON, in which a
     ;; percent sign is just a character.
     :form (when (not= "/mcp" (.getRawPath uri)) (some-> body form-params))}))

(defn- send! [^HttpExchange ex {:keys [status headers body]}]
  (let [bytes (.getBytes (str body) StandardCharsets/UTF_8)
        hs (.getResponseHeaders ex)]
    (.set hs "Content-Type" (get headers "Content-Type" "text/html; charset=utf-8"))
    (.set hs "X-Content-Type-Options" "nosniff")
    (.set hs "Content-Security-Policy" "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' https: data:; form-action 'self'; frame-ancestors 'none'")
    (doseq [[k v] headers] (.set hs k v))
    (.sendResponseHeaders ex status (if (empty? bytes) -1 (alength bytes)))
    (with-open [out (.getResponseBody ex)] (.write out bytes))))

(defn surfaces
  "What a server offers: the names in `given` (comma-separated http and
   mcp), else http, plus mcp when MCP has a token."
  [given token?]
  (let [s (if (str/blank? given)
            (cond-> #{"http"} token? (conj "mcp"))
            (set (map str/trim (str/split given #","))))]
    (when-let [unknown (seq (remove #{"http" "mcp"} s))]
      (throw (ex-info (str "KAJERO_SURFACES knows http and mcp, not " (str/join "," unknown)) {})))
    (when (and (s "mcp") (not token?))
      (throw (ex-info "KAJERO_SURFACES=mcp needs KAJERO_MCP_TOKEN_FILE" {})))
    s))

(defn- log!
  "A line on stderr, which the service hands to the journal."
  [& parts]
  (binding [*out* *err*] (apply println parts)))

(defn- log-request!
  "One line per request: method, path without its query, status and
   milliseconds. The query is left out so search words stay out of the log."
  [^HttpExchange ex status started]
  (log! (.getRequestMethod ex) (.getRawPath (.getRequestURI ex)) status
        (str (quot (- (System/nanoTime) started) 1000000) "ms")))

(defn- log-error!
  "An internal error with its stack trace; the client sees only the message,
   so this is the server's record of it."
  [^HttpExchange ex ^Throwable t]
  (let [trace (java.io.StringWriter.)]
    (.printStackTrace t (java.io.PrintWriter. trace))
    (log! "internal error on" (.getRequestMethod ex) (.getRawPath (.getRequestURI ex)) "\n" (str trace))))

(defn mcp-token
  "The bearer token in the text of a token file. A short one is refused: the
   token is all that stands between a local process and the MCP writer."
  [text]
  (let [token (str/trim (str text))]
    (when (< (count token) 32)
      (throw (ex-info "KAJERO_MCP_TOKEN_FILE must hold a token of at least 32 characters" {})))
    token))

(defn start!
  "Starts the server on 127.0.0.1:`port`; returns it, for `.stop`. With
   `mcp` {:token :writer}, it also serves MCP on /mcp; with `pages?`
   false, it serves nothing but that."
  ^HttpServer [nb writer port & [mcp pages?]]
  (let [token (.formatHex (HexFormat/of) (let [b (byte-array 24)] (.nextBytes (SecureRandom.) b) b))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [started (System/nanoTime)
                              status (try
                                       (let [response (or (let [request (exchange->request ex)]
                                                            (or (request-guard port request)
                                                                (cond
                                                                  (= "/mcp" (:path request)) (mcp-endpoint nb (assoc mcp :port port) request)
                                                                  (false? pages?) nil
                                                                  :else (respond nb writer token request))))
                                                          {:status 404 :body (layout {:title "Not found"} [:h1 "Not found"]
                                                                                     [:p.empty "No such page or entry."])})]
                                         (send! ex response)
                                         (:status response 200))
                                       ;; The HTTP boundary: any failure, an Error included,
                                       ;; answers 500 instead of leaving the exchange open.
                                       #_{:splint/disable [lint/catch-throwable]}
                                       (catch Throwable t
                                         (log-error! ex t)
                                         (send! ex {:status 500 :body (str "internal error: " (ex-message t))})
                                         500)
                                       (finally (.close ex)))]
                          (log-request! ex status started)))))
    (.start server)
    server))

(defn -main
  [& _]
  (let [env #(not-empty (System/getenv %))
        port (Long/parseLong (or (env "KAJERO_PORT") "7270"))
        nb (k/notebook {:dir (env "KAJERO_DIR")
                        :path (or (env "KAJERO_DB") "notes.db")
                        :signing (keyword (or (env "KAJERO_SIGNING") "optional"))
                        :actors (env "KAJERO_ACTORS")
                        :tik-root (env "KAJERO_TIK_ROOT")})
        token-file (env "KAJERO_MCP_TOKEN_FILE")
        offered (surfaces (env "KAJERO_SURFACES") (some? token-file))
        mcp (when (offered "mcp")
              {:token (mcp-token (slurp token-file))
               :writer {:actor (env "KAJERO_MCP_ACTOR") :key (env "KAJERO_MCP_KEY")}})]
    (start! nb {:actor (env "KAJERO_ACTOR") :key (env "KAJERO_KEY")} port mcp (contains? offered "http"))
    (println (str "kajero on http://127.0.0.1:" port ":"
                  (when (offered "http") " pages")
                  (when (= 2 (count offered)) " and")
                  (when mcp " MCP on /mcp")))))
