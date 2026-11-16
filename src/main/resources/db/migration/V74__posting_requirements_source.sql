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
