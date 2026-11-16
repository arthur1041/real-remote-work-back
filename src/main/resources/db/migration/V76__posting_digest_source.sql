-- De que texto este resumo saiu.
--
-- **A terceira vez que esta mesma forma aparece hoje.** A V74 fez os
-- requisitos pararem de ser extraídos de novo quando a pergunta não mudou; a
-- V75 fez a lista de descoberta parar de ser jogada fora por uma edição que
-- não a decide. Esta é o resumo semântico, e o desperdício tem duas metades:
-- uma chamada ao modelo por versão nova, gasta de um teto diário que ela
-- divide com os perfis dos candidatos, e -- pior -- a janela entre a edição e
-- o resumo novo, em que a descoberta daquela vaga cai no caminho das
-- palavras, que é o pior dos dois.
--
-- O resumo é construído de título, categoria, regime, elegibilidade
-- geográfica, requisitos e descrição. Salário, benefícios, data de
-- encerramento, empresa contratante, destino da candidatura e perguntas do
-- formulário não entram nele -- e são as edições de todo dia.
--
-- chr(1) pela razão das duas anteriores: `text` no Postgres recusa o nulo.
alter table posting_digests add column source_hash text;

comment on column posting_digests.source_hash is
    'SHA-256 do que alimenta o resumo. Igual ao da versão anterior significa que não há o que resumir de novo.';

-- Os resumos da versão corrente saíram do texto de agora: ganham o carimbo e
-- sobrevivem ao deploy. Os de versões anteriores ficam nulos e nunca casam.
update posting_digests d
   set source_hash = encode(sha256(convert_to(
         coalesce(p.title,'') || chr(1) ||
         coalesce(p.category,'') || chr(1) ||
         coalesce(p.employment_kind,'') || chr(1) ||
         coalesce(p.geo_scope,'') || chr(1) ||
         coalesce(p.geo_detail,'') || chr(1) ||
         coalesce(p.description_source,''), 'UTF8')), 'hex')
  from employer_postings p
 where p.id = d.posting_id
   and d.content_version = p.content_version
   and d.state = 'OK';
