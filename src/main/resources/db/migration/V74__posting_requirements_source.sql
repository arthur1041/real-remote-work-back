-- De que texto estes requisitos saíram.
--
-- **O problema, medido em 16 Nov 2026.** Uma vaga teve só o título editado --
-- a descrição não mudou um caractere -- e as três candidaturas dela foram
-- relidas a preço cheio, 3.112 tokens. O motivo: `extract` manda título e
-- descrição ao modelo a cada versão nova, e o modelo reescreveu um dos nove
-- requisitos, "Professional English" virou "Professional English, written and
-- spoken". O `carryForward` compara os conjuntos como texto, oito de nove
-- batiam, e por causa do nono nada foi reaproveitado.
--
-- A raiz não é a comparação: é extrair de novo. Se a descrição e o tipo de
-- contratação são os mesmos, a pergunta é a mesma, e perguntá-la outra vez a
-- um modelo não determinístico só pode piorar -- custa uma chamada e sorteia
-- uma redação nova que invalida todas as leituras guardadas.
--
-- Esta coluna guarda o hash do que gerou cada conjunto, para que a versão
-- seguinte saiba reconhecer que não tem nada a perguntar. Linhas anteriores a
-- esta migração ficam com null e nunca casam, que é o comportamento de hoje.
--
-- **O título fica de fora do hash de propósito.** Ele vai no prompt como
-- contexto, mas quem define o que se exige do candidato é a descrição. Incluí-lo
-- faria um erro de digitação no título custar uma releitura por candidatura --
-- exatamente o gasto que o carry-forward existe para evitar.
alter table posting_requirements add column source_hash text;

comment on column posting_requirements.source_hash is
    'SHA-256 de description_source + employment_kind. Igual ao da versão anterior significa que não há nada a extrair de novo.';

-- **O carimbo nas linhas que já existem.**
--
-- Sem isto, `rolesDue` -- que passou a considerar lida só a versão com hash --
-- veria toda vaga de assinante como nunca extraída, e no primeiro minuto
-- depois do deploy extrairia todas de novo. Cada reextração é uma chamada ao
-- modelo e, se a redação sair diferente, uma releitura por candidatura: o
-- gasto exato que esta migração existe para evitar, cobrado uma vez no deploy.
--
-- Só a versão corrente de cada vaga é carimbada, porque é a única cujo texto
-- ainda está guardado: `employer_postings` tem a descrição de agora, não a de
-- cada versão. As linhas de versões anteriores ficam nulas e nunca casam, que
-- é o comportamento correto para elas.
--
-- **Esta migração roda uma vez, antes de qualquer linha nula escrita pelo
-- código novo, e é isso que a torna segura.** Depois dela, hash nulo passa a
-- ter um segundo significado -- extração que não obteve resposta -- e carimbar
-- uma dessas congelaria um conjunto incompleto. Aqui esse caso não existe
-- ainda: tudo que está nulo neste instante saiu do texto que está guardado ao
-- lado. Se algum dia for preciso reexecutar isto à mão, exclua antes as
-- linhas de extrações falhas, ou o carimbo vai dizer que elas são a resposta.
--
-- O separador é chr(1) e não chr(0) porque `text` no Postgres recusa o
-- caractere nulo. `ApplicationScreener.sourceHash` usa o mesmo byte, e os dois
-- têm de continuar iguais -- é o que faz esta linha e aquele método
-- responderem a mesma coisa sobre a mesma vaga.
update posting_requirements r
   set source_hash = encode(
           sha256(convert_to(
               coalesce(p.description_source, '') || chr(1) || coalesce(p.employment_kind, ''),
               'UTF8')),
           'hex')
  from employer_postings p
 where p.id = r.posting_id
   and r.content_version = p.content_version
   and r.source_hash is null;

-- **Em que versão os requisitos desta vaga foram perguntados.**
--
-- A fila dizia "devida é a versão sem requisito nenhum", e isso não distingue
-- duas coisas muito diferentes: ninguém perguntou ainda, e perguntou-se e a
-- resposta foi *nenhum requisito*. A segunda é legítima -- o prompt manda
-- devolver lista vazia quando a vaga não exige nada, e o requisito de campo só
-- existe quando o employer escolheu um tipo de contratação, que é opcional no
-- formulário.
--
-- Juntas, as duas condições davam um laço: a vaga ficava devida para sempre e
-- o modelo era perguntado uma vez por minuto, 1.440 vezes por dia, sem que
-- nada aparecesse no banco para interromper. Nenhum teto segura isso: o
-- disjuntor diário conta leituras de candidatura, não extrações.
-- Reproduzido em 16 Nov 2026 com uma descrição sem exigências e sem tipo de
-- contratação.
--
-- Esta coluna responde a pergunta certa. É escrita só quando houve resposta,
-- de modo que uma extração que falhou continua devida e é tentada de novo --
-- e uma que respondeu "nada" para de ser perguntada até o texto mudar.
alter table employer_postings add column requirements_version integer;

comment on column employer_postings.requirements_version is
    'content_version cuja extração de requisitos obteve resposta. Diferente de content_version significa que a fila ainda tem o que perguntar.';

-- As que já têm requisitos na versão corrente já foram perguntadas.
update employer_postings p
   set requirements_version = p.content_version
 where exists (
     select 1 from posting_requirements r
      where r.posting_id = p.id and r.content_version = p.content_version
 );
