-- PROPOSED, NOT APPLIED. Needs the project owner's approval before it is run on any database.
--
-- Leaving and deleting chats, like WhatsApp. Requires V22. Adds columns only; no existing row is changed.
--
--   chat_members.cleared_message_id  "Delete chat/group" for one student: messages up to this id are gone for them
--   chat_members.hidden              the chat is off that student's list until a newer message reaches them
--   chat_members.left_message_id     after leaving: the last message they can still read (nothing newer reaches them)
--   chat_members.left_by_choice      they left their class group themselves, so it does not add them back
--   chat_conversations.deleted_at/by the owner deleted a study group for everyone (it is also archived)
--
-- Existing rows keep their values: nobody has cleared or hidden anything, and members who left before V25 (with no
-- left_message_id) stay out of sight as before.

ALTER TABLE public.chat_members
    ADD COLUMN cleared_message_id bigint NOT NULL DEFAULT 0,
    ADD COLUMN hidden boolean NOT NULL DEFAULT false,
    ADD COLUMN left_message_id bigint,
    ADD COLUMN left_by_choice boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_chat_member_cleared CHECK (cleared_message_id >= 0),
    ADD CONSTRAINT ck_chat_member_left_window CHECK (left_message_id IS NULL OR (left_at IS NOT NULL AND left_message_id >= 0)),
    ADD CONSTRAINT ck_chat_member_left_choice CHECK (NOT left_by_choice OR left_at IS NOT NULL);

ALTER TABLE public.chat_conversations
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by bigint,
    ADD CONSTRAINT ck_chat_conversation_deleted CHECK ((deleted_at IS NULL) = (deleted_by IS NULL)
        AND (deleted_at IS NULL OR (kind = 'GROUP' AND archived_at IS NOT NULL))),
    ADD CONSTRAINT fk_chat_conversation_deleted_by FOREIGN KEY (college_id, deleted_by)
        REFERENCES public.student_profiles (college_id, student_id) ON DELETE RESTRICT;
