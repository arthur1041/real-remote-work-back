-- Uma leitura que foi aproveitada de uma versão anterior da vaga, em vez de
-- refeita.
--
-- **O problema que isto resolve, medido em 15 Nov 2026.** A fila da triagem é
-- "candidatura sem leitura *nesta versão do texto*", e `content_version` sobe a
-- cada salvamento da vaga -- inclusive salvando o formulário sem mexer em nada,
-- o que é de propósito, para ninguém escapar de uma recusa re-salvando. A
-- consequência não era de propósito: corrigir uma vírgula numa vaga com 200
-- candidaturas mandava as 200 serem lidas de novo. Em dinheiro é pouco
-- (0,19 centavo por leitura, ~US$ 0,38); em cota é 20% do teto mensal do
-- assinante, gasto sem que nada na tela tenha oferecido essa troca.
--
-- **A regra nova.** Os requisitos da nova versão são extraídos como sempre
-- (uma chamada por vaga, não por candidatura). Se o conjunto for idêntico ao
-- da versão anterior -- mesmo texto, mesma marca de essencial, mesma origem --
-- as leituras que já existiam são copiadas para a versão nova em vez de
-- refeitas. Mudar o salário, o link, o título ou uma vírgula passa a custar
-- uma chamada; mudar o que a vaga *pede* continua custando uma leitura por
-- candidatura, porque aí a pergunta mudou de verdade.
--
-- **Por que uma coluna e não um booleano.** Guardar de qual leitura esta veio
-- deixa a procedência consultável: o custo do mês é a soma dos tokens das
-- linhas sem `carried_from`, e o teto mensal do assinante conta só essas --
-- uma cópia não gastou modelo nenhum e não pode gastar cota.
alter table application_screenings
    add column carried_from bigint references application_screenings(id) on delete set null;

create index application_screenings_carried
    on application_screenings (carried_from)
 where carried_from is not null;
