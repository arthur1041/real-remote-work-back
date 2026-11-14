-- ============================================================================
-- O que as pessoas digitam na caixa de busca.
--
-- **Por que isto não existia e precisa existir.** Em 14 Nov 2026 a única forma
-- de saber o que alguém procurou aqui foi reconstruir 29 termos a partir do
-- log de acesso do Caddy -- que rotaciona em horas, então a janela inteira
-- disponível era de um dia. Metade do que esse dia mostrou é informação que
-- não aparece em nenhum outro lugar do sistema:
--
--   * 12 dos 29 termos não devolveram nada, e nove deles eram um título de
--     vaga colado inteiro ("Aerospace Engineering Expert");
--   * `Data entry`, `Medical affairs`, `Litigation Attorney`, `Aviation
--     Subject Matter Expert` e `Abstractor` são demanda que este quadro não
--     atende -- ninguém olhando a base saberia disso, porque a base não tem
--     essas vagas;
--   * `data engineer`, `data analyst`, `graphic designer` são demanda que ele
--     atende e cuja página gerada é escolhida à mão em `lib/searches.ts`, numa
--     lista de 28 termos que é toda de tecnologia e negócios.
--
-- Essas duas listas são, respectivamente, a fila de ingestão e o catálogo de
-- páginas indexáveis. Nenhuma das duas pode ser derivada do corpus: são o que
-- falta nele.
--
-- **Não identifica ninguém.** Nem IP, nem sessão, nem candidato, nem cookie --
-- nada que ligue duas buscas à mesma pessoa. O que fica é a frase, quando foi
-- digitada, e quantos resultados ela devolveu. É a mesma promessa que
-- `job_daily_stats` faz desde a V17, e pelo mesmo motivo: a pergunta útil aqui
-- é agregada por termo e não por quem.
--
-- **O texto cru fica ao lado do normalizado.** "graphic designe" e "graphic
-- designer" são a mesma intenção e termos diferentes; agrupar só pelo
-- normalizado responde "quantos procuraram designer", e guardar o cru é o que
-- mostra que um deles foi digitado errado e ainda assim funcionou (o stemmer
-- do Postgres resolveu). Limitado no app a 120 caracteres.
--
-- **Tamanho.** 50 buscas humanas por dia, medido. Dezoito mil linhas por ano,
-- com um índice. Não vale uma tabela agregada por dia nem uma rotina de poda.
-- ============================================================================

create table search_queries (
    id         bigserial   primary key,
    -- Minúsculas, sem pontuação, espaços colapsados: a chave do group by.
    term       text        not null,
    -- Como foi digitado.
    raw        text        not null,
    -- 'WORLDWIDE' (o padrão do quadro), 'ANY' quando o leitor abriu mão dele,
    -- ou a lista de escopos que ele pediu.
    scope      text,
    -- Quantas vagas a busca devolveu. Zero é o número mais interessante aqui.
    results    integer     not null,
    -- Verdadeiro quando nada casou com todas as palavras e a página caiu para
    -- qualquer uma delas. Mede o quanto o fallback está carregando a busca.
    relaxed    boolean     not null default false,
    -- Quantos outros filtros estavam ativos. Uma busca com seis filtros que
    -- devolve zero não é o mesmo problema que uma palavra sozinha que devolve
    -- zero.
    filters    integer     not null default 0,
    created_at timestamptz not null default now()
);

comment on table search_queries is
    'Frases digitadas na busca, sem nada que identifique quem digitou. '
    'Existe para duas perguntas que o corpus não responde: que demanda o '
    'quadro não atende, e que termo merece uma página gerada.';

-- A consulta é sempre "os termos dos últimos N dias", então o recorte é por
-- data e o agrupamento acontece depois, sobre poucas linhas.
create index search_queries_recent_idx on search_queries (created_at desc);
