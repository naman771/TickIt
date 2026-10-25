-- Optional organizer-authored, plain-text information. Existing listings remain valid.
ALTER TABLE event
  ADD COLUMN languages varchar(160),
  ADD COLUMN artists varchar(1000),
  ADD COLUMN entry_instructions varchar(3000),
  ADD COLUMN accessibility_info varchar(1000),
  ADD COLUMN ticket_inclusions varchar(3000),
  ADD COLUMN faq varchar(4000);
