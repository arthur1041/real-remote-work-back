-- De que versão da vaga esta lista saiu, pelo que de fato a decide.
--
-- **O problema, medido em 16 Nov 2026.** A lista de descoberta era procurada
-- por `content_version`, e qualquer edição da vaga cria versão nova. Uma lista
-- comprada -- uma das cinco do mês, ou um crédito de até $2,98 -- desaparecia
-- quando o employer acrescentava um benefício, punha data de encerramento ou
-- corrigia o título, e o painel voltava a oferecer "construir uma", cobrando
-- de novo por uma lista que teria saído idêntica.
--
-- **O que realmente decide uma lista.** O título e a categoria, de onde saem
-- as palavras do filtro; o regime de contratação e a elegibilidade
-- geográfica, que dizem quem pode pegar a vaga; e a descrição, de onde sai o
-- resumo que alimenta o caminho semântico. Nada mais: salário, benefícios,
-- data de encerramento, empresa contratante, destino da candidatura e as
-- perguntas do formulário não mudam uma única linha da lista -- e são
-- exatamente as edições mais comuns.
--
-- O separador é chr(1) pela mesma razão da V74: `text` no Postgres recusa o
-- caractere nulo, e a expressão precisa ser reproduzível em SQL.
alter table discovery_rankings add column source_hash text;

comment on column discovery_rankings.source_hash is
    'SHA-256 do que decide a lista: title, category, employment_kind, geo_scope, geo_detail, geo_excluded, description_source. Igual ao da vaga hoje significa que a lista ainda responde a mesma pergunta.';

-- As listas que já estão na versão corrente são, por definição, as que saíram
-- do texto de agora: ganham o carimbo e sobrevivem ao deploy. As de versões
-- anteriores ficam nulas e nunca casam, que é o comportamento de hoje.
update discovery_rankings r
   set source_hash = encode(sha256(convert_to(
         coalesce(p.title,'') || chr(1) ||
         coalesce(p.category,'') || chr(1) ||
         coalesce(p.employment_kind,'') || chr(1) ||
         coalesce(p.geo_scope,'') || chr(1) ||
         coalesce(p.geo_detail,'') || chr(1) ||
         coalesce(array_to_string(p.geo_excluded, ','), '') || chr(1) ||
         coalesce(p.description_source,''), 'UTF8')), 'hex')
  from employer_postings p
 where p.id = r.posting_id
   and r.content_version = p.content_version;
