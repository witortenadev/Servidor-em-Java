// importando classes de HTTP

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

// importanto outras funcionalidades

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class App {

    static final int PORT = 8080; // determinando a porta como 8080
    static final String CSV = "data_tasks.csv";
    static final int MAX = 5000;

    // declarando um array de strings com tamanho máximo de 5000 items para ids, titulos, descrições, status e momento de criação
    static String[] ids = new String[MAX];
    static String[] titulos = new String[MAX];
    static String[] descrs = new String[MAX];
    static int[] status = new int[MAX];     // 0 TODO, 1 DOING, 2 DONE
    static long[] criados = new long[MAX];
    static int n = 0;

    public static void main(String[] args) throws Exception {
        carregar(); // chama o método carregar

        // inicia um servidor e configura ele para escutar a porta 8080
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new RootHandler()); // cria uma rota root
        server.createContext("/api/tasks", new ApiTasksHandler()); // cria uma rota em "/api/tasks"
        server.setExecutor(null);
        System.out.println("Servindo em http://localhost:" + PORT);
        server.start(); // inicia o servidor
    }


    static class RootHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { send(ex, 405, ""); return; } // se o método HTTP não for GET, retornar código 405 (método não permitido)
            byte[] body = INDEX_HTML.getBytes(StandardCharsets.UTF_8); // busca os conteúdos de body, presente no request
            // configurar reposta (response)
            // e enviar código 200 (OK) junto do que o usuário envio para o servidor no request
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); } // tentativa de envio de uma response
        }
    }

    // gerenciamento da API
    static class ApiTasksHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod(); // busca o método HTTP usado
            URI uri = ex.getRequestURI();
            String path = uri.getPath(); // busca a URL que o usuário acessou

            try {
                // caso a URL atual do usuário for /api/tasks e o método HTTP for GET
                if ("GET".equals(method) && "/api/tasks".equals(path)) {
                    sendJson(ex, 200, listarJSON()); // retornar código 200 junto de uma lista de JSON
                    return;
                }

                // caso a URL atual do usuário for /api/tasks e o método HTTP for POST
                if ("POST".equals(method) && "/api/tasks".equals(path)) {
                    // buscar conteúdo do body do request
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    // dentro do body, procurar por "titulo" e "descricao"
                    String titulo = jsonGet(body, "titulo");
                    String descricao = jsonGet(body, "descricao");

                    // caso não tenha título ou o titulo for vazio
                    if (titulo == null || titulo.isBlank()) {
                        sendJson(ex, 400, "{\"error\":\"titulo obrigatório\"}"); // enviar response dizendo que o titulo é obrigatório
                        return;
                    } // senão
                    Map<String, Object> t = criar(titulo, descricao == null ? "" : descricao); // Criar uma task (tarefa)
                    salvar();
                    // enviar código OK e a task criada
                    sendJson(ex, 200, toJsonTask(t));
                    return;
                }

                // caso a URL atual do usuário for /api/tasks/status e o método HTTP for PATCH
                if ("PATCH".equals(method) && path.startsWith("/api/tasks/") && path.endsWith("/status")) {

                    // determinar o id da task a ser buscada por meio da URL
                    String id = path.substring("/api/tasks/".length(), path.length() - "/status".length());
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); // buscar body do request
                    String stStr = jsonGet(body, "status"); // buscar "status" presente no body

                    // caso não tenha um status presente, enviar mensagem de erro com código 400 e a mensagem abaixo (client side error)
                    if (stStr == null) { sendJson(ex, 400, "{\"error\":\"status ausente\"}"); return; }

                    int st = clampStatus(parseIntSafe(stStr, 0)); // delimitar a string presente no "status" do body
                    int i = findIdxById(id); // procurar por um Id com o valor presente na URL
                    if (i < 0) { sendJson(ex, 404, "{\"error\":\"not found\"}"); return; } // caso não exista, enviar código 404 (not found)
                    status[i] = st; // definir o status na posição i como a string presente em "status" delimitada
                    salvar();
                    sendJson(ex, 200, toJsonTask(mapOf(i))); // enviar cógido 200 (OK) junto da task em JSON
                    return;
                }

                // caso a URL atual do usuário for /api/tasks/ e o método HTTP for Delete
                if ("DELETE".equals(method) && path.startsWith("/api/tasks/")) {
                    String id = path.substring("/api/tasks/".length()); // determinar o id da task
                    int i = findIdxById(id); // encontrar a task com o id presente na URL
                    if (i < 0) { sendJson(ex, 404, "{\"error\":\"not found\"}"); return; } // enviar erro caso não encontre

                    // passa o valor das variáveis da task 2 para a task 1 (exemplo)
                    for (int k = i; k < n - 1; k++) {
                        ids[k] = ids[k+1]; titulos[k] = titulos[k+1]; descrs[k] = descrs[k+1];
                        status[k] = status[k+1]; criados[k] = criados[k+1];
                    }
                    n--;
                    salvar();
                    sendJson(ex, 204, ""); // enviar código OK
                    return;
                }

                send(ex, 404, ""); // enviar código de não encontrado
            } catch (Exception e) {
                // caso ocorra um erro enviar mensagem de erro com código 500 (erro no servidor)
                e.printStackTrace();
                sendJson(ex, 500, "{\"error\":\"server\"}");
            }
        }
    }


    // determinar o HTML da página (Frontend)
    static final String INDEX_HTML = """
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Kanban Local (sem framework)</title>
<style>
  :root{--bg:#f6f7fb;--card:#fff;--muted:#666;}
  *{box-sizing:border-box} body{margin:0;font:16px system-ui,Segoe UI,Roboto,Arial;background:var(--bg)}
  header{background:#111;color:#fff;padding:12px 16px}
  .wrap{max-width:1100px;margin:0 auto;padding:16px}
  form{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}
  input,textarea,button,select{border:1px solid #ddd;border-radius:10px;padding:10px;font:inherit}
  textarea{min-width:260px;min-height:40px}
  button{cursor:pointer}
  .board{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}
  .col{background:var(--card);border-radius:14px;box-shadow:0 6px 16px rgba(0,0,0,.06);padding:12px}
  .col h2{margin:6px 4px 10px}
  .task{border:1px solid #eee;border-radius:12px;background:#fafafa;margin:8px 0;padding:10px}
  .row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
  small{color:var(--muted)}
  .pill{font-size:.75rem;padding:2px 8px;border-radius:999px;background:#eef;border:1px solid #dde}
</style>
</head>
<body>
<header><b>Kanban Local</b> — Gestão de Atividades</header>
<div class="wrap">

  <h3>Nova tarefa</h3>
  <form id="f">
    <input id="t" placeholder="Título" required>
    <textarea id="d" placeholder="Descrição (opcional)"></textarea>
    <button>Adicionar</button>
  </form>

  <div class="board">
    <div class="col"><h2>To-Do</h2><div id="todo"></div></div>
    <div class="col"><h2>Doing</h2><div id="doing"></div></div>
    <div class="col"><h2>Done</h2><div id="done"></div></div>
  </div>

</div>

<script>
const API = "/api/tasks";

async function listar(){
  const r = await fetch(API);
  const data = await r.json();
  render(data);
}

function el(html){
  const t = document.createElement('template');
  t.innerHTML = html.trim();
  return t.content.firstChild;
}

// CORRIGIDO: função sem erro de sintaxe
function escapeHtml(s){
  return s.replace(/[&<>\"']/g, c => ({
    '&':'&amp;',
    '<':'&lt;',
    '>':'&gt;',
    '\"':'&quot;',
    \"'\":'&#039;'
  }[c]));
}

function card(t){
  const div = el(`<div class="task">
      <strong>${escapeHtml(t.titulo)}</strong>
      <div class="row">
        <span class="pill">${['TODO','DOING','DONE'][t.status] || t.status}</span>
        <small>criado: ${new Date(t.criadoEm).toLocaleString()}</small>
      </div>
      <p>${escapeHtml(t.descricao||'')}</p>
      <div class="row">
        ${t.status!==0?'<button data-prev>◀</button>':''}
        ${t.status!==2?'<button data-next>▶</button>':''}
        <button data-del>Excluir</button>
      </div>
  </div>`);

  if(div.querySelector('[data-prev]')){
    div.querySelector('[data-prev]').onclick=()=>mover(t.id, t.status===2?1:0);
  }
  if(div.querySelector('[data-next]')){
    div.querySelector('[data-next]').onclick=()=>mover(t.id, t.status===0?1:2);
  }
  div.querySelector('[data-del]').onclick=()=>excluir(t.id);
  return div;
}

function render(arr){
  ['todo','doing','done'].forEach(id=>document.getElementById(id).innerHTML='');
  arr.filter(x=>x.status===0).forEach(x=>document.getElementById('todo').appendChild(card(x)));
  arr.filter(x=>x.status===1).forEach(x=>document.getElementById('doing').appendChild(card(x)));
  arr.filter(x=>x.status===2).forEach(x=>document.getElementById('done').appendChild(card(x)));
}

document.getElementById('f').onsubmit=async (e)=>{
  e.preventDefault();
  const titulo = document.getElementById('t').value.trim();
  const descricao = document.getElementById('d').value.trim();
  if(!titulo) return;
  await fetch(API,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({titulo,descricao})});
  e.target.reset(); listar();
};

async function mover(id,status){
  await fetch(`${API}/${id}/status`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({status})});
  listar();
}
async function excluir(id){
  await fetch(`${API}/${id}`,{method:'DELETE'}); listar();
}

listar();
</script>
</body>
</html>
""";


    static void carregar() {
        n = 0;
        Path p = Paths.get(CSV); // usar o arquivo cujo caminho é a variável CSV
        if (!Files.exists(p)) return; // caso ele não existir, retornar
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) { // enquando a linha for nula
                // caso a linha estiver vazia ou a linha começar com "id;" continuar execução
                if (line.isBlank() || line.startsWith("id;")) continue;
                String[] a = splitCsv(line); // dividir o arquivo na linha indicada e guardar data nessa var
                if (a.length < 5) continue;  // caso a quantidade de chars for menor que 5, continue
                if (n >= MAX) break;         // caso n seja maior ou igual a 5000 parar execução

                // ids, titulos, descrição, status, na posição n recebe o id, titulo descrição e status presentes em a
                ids[n] = a[0];
                titulos[n] = a[1];
                descrs[n] = a[2];
                status[n] = clampStatus(parseIntSafe(a[3], 0));
                criados[n] = parseLongSafe(a[4], System.currentTimeMillis());
                n++;
            }
        } catch (IOException e) { // exibir mensagem de erro
            System.out.println("Falha ao ler CSV: " + e.getMessage());
        }
    }

    static void salvar() {
        Path p = Paths.get(CSV); // buscar arquivo CSV
        try {
            // caso o arquivo tenha um diretório pai, criar um novo nesse diretório
            if (p.getParent()!=null) Files.createDirectories(p.getParent());

            // escrever uma tarefa no arquivo CSV
            try (BufferedWriter bw = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                bw.write("id;titulo;descricao;status;criadoEm\n");
                for (int i = 0; i < n; i++) {
                    bw.write(esc(ids[i]) + ";" + esc(titulos[i]) + ";" + esc(descrs[i]) + ";"
                            + status[i] + ";" + criados[i] + "\n");
                }
            }
        } catch (IOException e) { // caso ocorra um erro durante a escrita, imprimir o erro no terminal
            System.out.println("Falha ao salvar CSV: " + e.getMessage());
        }
    }

    static Map<String, Object> criar(String titulo, String descr) {
        // caso não tenha mais espaço para criar tarefas retornar um erro
        if (n >= MAX) throw new RuntimeException("Capacidade cheia");

        // determinar id
        String id = UUID.randomUUID().toString().substring(0,8);

        // criar nova tarefa na posição n dos arrays
        ids[n]=id; titulos[n]=titulo; descrs[n]=descr; status[n]=0; criados[n]=System.currentTimeMillis();
        n++;
        return mapOf(n-1);
    }

    // funcao para identificar a posição de uma tarefa por meio do id passado como argumento
    static int findIdxById(String id){
        for (int i=0;i<n;i++) if (ids[i].equals(id)) return i;
        return -1;
    }

    static Map<String,Object> mapOf(int i){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id", ids[i]); m.put("titulo", titulos[i]); m.put("descricao", descrs[i]);
        m.put("status", status[i]); m.put("criadoEm", criados[i]);
        return m; // retornar uma mapa com as informações de uma tarefa na posição i
    }

    static String listarJSON(){ // retornar todas as tarefas em uma lista JSON
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<n;i++){
            if (i>0) sb.append(',');
            sb.append(toJsonTask(mapOf(i)));
        }
        sb.append(']');
        return sb.toString();
    }


    static String toJsonTask(Map<String,Object> t){ // retornar tarefa em JSON por meio do mapa passado (t)
        return "{\"id\":\""+jsonEsc((String)t.get("id"))+"\"," +
                "\"titulo\":\""+jsonEsc((String)t.get("titulo"))+"\"," +
                "\"descricao\":\""+jsonEsc((String)t.get("descricao"))+"\"," +
                "\"status\":" + t.get("status") + "," +
                "\"criadoEm\":" + t.get("criadoEm") + "}";
    }


    // buscar dados presentes no body de um request
    static String jsonGet(String body, String key){
        if (body == null) return null; // caso não tenha nada no body, retornar nulo
        String s = body.trim();

        // remover chaves
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length()-1);

        // criar uma lista com partes presentes no body
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i=0;i<s.length();i++){
            char c = s.charAt(i);
            if (c=='"' && (i==0 || s.charAt(i-1)!='\\')) inQ = !inQ;
            if (c==',' && !inQ){ parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        if (cur.length()>0) parts.add(cur.toString());

        for (String kv : parts){ // pegar os dados presentes no JSON para cada key value pair
            int i = kv.indexOf(':');
            if (i<=0) continue;
            String k = kv.substring(0,i).trim();
            String v = kv.substring(i+1).trim();
            k = stripQuotes(k);
            if (key.equals(k)){
                v = stripQuotes(v);
                return v;
            }
        }
        return null;
    }

    static String stripQuotes(String s){ // remover aspas
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length()-1).replace("\\\"", "\"");
        }
        return s;
    }

    // enviar dados
    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // enviar dados em JSON
    static void sendJson(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        send(ex, code, body==null?"":body);
    }

    static String esc(String s){ // retorna a string passada entre aspas
        if (s==null) return "";
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
    static String[] splitCsv(String line){
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i=0;i<line.length();i++){
            char c = line.charAt(i);
            if (inQ){
                if (c=='"'){
                    if (i+1<line.length() && line.charAt(i+1)=='"'){ cur.append('"'); i++; }
                    else inQ=false;
                } else cur.append(c);
            } else {
                if (c==';'){ out.add(cur.toString()); cur.setLength(0); }
                else if (c=='"'){ inQ=true; }
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
    static String jsonEsc(String s){
        if (s==null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");
    }

    // outras funções úteis
    static int clampStatus(int s){ return Math.max(0, Math.min(2, s)); }
    static int parseIntSafe(String s, int def){ try { return Integer.parseInt(s.trim()); } catch(Exception e){ return def; } }
    static long parseLongSafe(String s, long def){ try { return Long.parseLong(s.trim()); } catch(Exception e){ return def; } }
}

