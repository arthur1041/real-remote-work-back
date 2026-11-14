/*
 * A candidatura que sai daqui, e por que ela é uma linha desta tabela.
 *
 * Uma vaga pode receber candidatura de três formas: aqui no site, numa página
 * do próprio employer, ou por email. Nas duas últimas a pessoa clica em
 * "Apply" e vai embora -- e até hoje o que ficava era um clique contado em
 * `candidate_apply_clicks`, sem nome, sem CV e sem nada que se pudesse ler.
 *
 * Para quem assina o Growth isso significava que a funcionalidade inteira --
 * ler cada candidatura contra o que a vaga pede -- não existia para metade
 * das vagas do próprio assinante. Ele paga pela leitura e a vaga dele não é
 * lida, porque a candidatura acontece fora.
 *
 * O que muda: antes de mandar a pessoa embora, o site pede uma confirmação
 * curta -- o regime que ela aceita PARA ESTA VAGA -- e guarda isso como uma
 * candidatura de origem REFERRED. A máquina de leitura já sabe ler uma linha
 * de `job_applications`; passa a ler estas também, sem caminho novo.
 *
 * `source` e não uma tabela separada, de propósito: duas tabelas seriam duas
 * definições de "alguém se candidatou a esta vaga", e a que não é lida por
 * ninguém é a que sai de sincronia. O default é PLATFORM, então toda linha
 * que já existe continua significando exatamente o que significava.
 *
 * **E a distinção é dita em voz alta na tela.** Uma pessoa que clicou para
 * sair não é uma pessoa que se candidatou: pode ter desistido no site do
 * employer. Mostrar as duas na mesma lista, sem rótulo, seria vender como
 * candidatura o que é interesse declarado. A coluna existe para que a tela
 * possa separar as duas, e não para que ela possa juntá-las.
 */
alter table job_applications
    add column source text not null default 'PLATFORM'
        check (source in ('PLATFORM', 'REFERRED'));

/*
 * Uma linha REFERRED não tem CV anexado nem respostas ao formulário do
 * employer -- não há formulário, a vaga é externa. O que ela tem é o perfil, o
 * CV da conta e a confirmação. Por isso `resume_id` já era nulo-permitido e
 * nada aqui muda; o que se registra é de onde veio.
 */
comment on column job_applications.source is
    'PLATFORM: preencheu o formulário aqui. REFERRED: confirmou o perfil e foi '
    'aplicar no site ou no email do employer.';

-- A lista do employer é sempre "esta vaga, desta origem, mais recente
-- primeiro". Sem isto ela varre todas as candidaturas da vaga para separar.
create index if not exists job_applications_posting_source_idx
    on job_applications (posting_id, source, created_at desc);
