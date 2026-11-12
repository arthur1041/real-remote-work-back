-- A descida de plano que ainda não aconteceu.
--
-- Growth -> Starter só vale na virada, e o plano fecha a direção numa linha:
-- subir é imediato porque o cliente está pagando mais, descer espera porque
-- ele já pagou. Entre o clique e a renovação a conta está num plano e deve
-- outro, e a linha não tinha como dizer isso -- ela carrega o que está em
-- vigor, e só.
--
-- **Duas colunas e nenhuma data, de propósito.** A virada é a renovação, que
-- é `expires_at`; uma terceira coluna com a data seria uma segunda cópia dela
-- livre para discordar. A tela diz uma data só por esse motivo.
--
-- **E nenhum id de agendamento.** O mecanismo é o `subscription_schedules` do
-- Stripe, alcançável a partir da assinatura que já guardamos
-- (`subscription.schedule`), então guardar o id aqui seria cache do estado de
-- terceiro sem nada que o mantenha verdadeiro. O que esta linha registra é o
-- que foi dito ao cliente, que é assunto deste lado.
alter table employer_subscriptions
  add column scheduled_plan  text,
  add column scheduled_cycle text;

alter table employer_subscriptions
  add constraint employer_subscriptions_scheduled_plan_check
    check (scheduled_plan is null or scheduled_plan in ('STARTER', 'GROWTH')),
  add constraint employer_subscriptions_scheduled_cycle_check
    check (scheduled_cycle is null or scheduled_cycle in ('MONTHLY', 'ANNUAL')),
  -- Os dois ou nenhum: plano sem ciclo não é cobrável e ciclo sem plano não é
  -- mudança nenhuma.
  add constraint employer_subscriptions_scheduled_pair_check
    check ((scheduled_plan is null) = (scheduled_cycle is null));
