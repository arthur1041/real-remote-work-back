-- A second kind of row in employer_email_verifications: a link instead of a code.
--
-- The code is right for the moment somebody registers: they are on the page,
-- the code arrives, they type it. It is wrong for a reminder sent days later
-- by an administrator to an account that never confirmed: the code would make
-- them find the page, sign in and type six digits inside fifteen minutes, which
-- is the friction that left them unconfirmed in the first place. A reminder
-- carries a link instead, and pressing it confirms the address.
--
-- Same table, because it is the same fact being proved and the same rules
-- apply: the row records the address it was sent to, which must still be the
-- account's when it is answered. `code_hash` holds the hash of the link's token
-- and `code_plain` the token until the mail goes, exactly as for a code. The
-- two kinds never answer for each other: typing a code looks only at CODE rows,
-- and asking for a new code does not cancel a reminder link.
alter table employer_email_verifications
    add column kind text not null default 'CODE'
        check (kind in ('CODE', 'LINK'));

comment on column employer_email_verifications.kind is
    'CODE: six digits typed on /employers/verify, 15 minutes. LINK: a reminder sent from the admin area, pressed rather than typed, 7 days.';
