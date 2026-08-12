# How the Incident Helper App Works — Like a Story

Imagine a big shop has many little machines. There are printers, internet boxes, cash-register machines, computers, and many other things. Sometimes one of them gets sick and stops working.

This app is like a **very careful helper robot**. It helps the grown-ups find the problem, read the right instructions, and suggest a safe fix. But it is **not allowed to do big things by itself**.

## 1. Someone says, “There is a problem!”

A problem can come to the helper robot in a few ways.

A different computer system, like ServiceNow or a monitoring tool, can send a message. A grown-up can also upload a spreadsheet, like a CSV or Excel file, full of problems. Or a grown-up can type a problem directly into the app.

For example, a message might say:

> “The printer in Shop 4 is offline.”

The app puts that message into its **problem queue**. A queue is like a line of children waiting for their turn.

## 2. The app checks, “Did we already hear this?”

Sometimes a machine keeps shouting the same problem again and again.

The app checks the problem’s name, where it came from, and its special ticket number. If it already has the same problem, it does not make lots of copies. It says:

> “I already know about this one.”

This keeps the grown-ups from getting a huge pile of duplicate problems.

## 3. The app gives the problem its own home

Big companies can have many teams. We can imagine each team has its own **toy box**.

When a person is signed in, the app knows which toy box belongs to that person’s team. The app puts the problem, suggested fix, approval request, and audit notes into that team’s toy box.

So Team A should not be looking inside Team B’s box.

## 4. The app looks in its instruction book

The app has a special instruction book called the **SOP book**.

SOP means “Standard Operating Procedure.” That is a grown-up way of saying:

> “These are the safe steps we already agreed to use.”

A grown-up can add SOPs by typing them in or uploading PDF, DOCX, or text documents. The app breaks the book into small pieces so it can find the right page quickly.

When there is a new problem, the app asks its SOP book something like:

> “What do our approved instructions say about an offline printer?”

If the book has a good answer, the app keeps that answer with its suggestion. If the book does **not** have a good answer, the app does not pretend it knows. It asks for a human instead.

## 5. The app makes a small, safe suggestion

The app only knows a tiny list of safe kinds of helper tools right now. For example:

| Problem clue | Small suggested helper tool |
|---|---|
| Printer problem | “Clear the printer queue” |
| Network, Wi-Fi, or VPN problem | “Refresh the network session” |
| A safe approved service is offline | “Restart the approved service” |

If the problem does not match one of these safe choices, the app says:

> “I do not know a safe tool for this. Please ask a human expert.”

It does not make up a random tool. It does not try a dangerous surprise.

## 6. The safety teachers check the suggestion

Before the app can show a fix to a grown-up, nine safety teachers look at it.

| Safety teacher | What the teacher asks |
|---|---|
| Permission teacher | “Is this person allowed to ask for a plan?” |
| Form teacher | “Does the problem message have the important information?” |
| Sneaky-word teacher | “Does this contain dangerous or trick words?” |
| Big-impact teacher | “Is this trying to touch too many machines?” |
| Practice teacher | “Will we do a pretend practice first?” |
| Too-many-times teacher | “Are we making the same plan too many times?” |
| Round-and-round teacher | “Is the app stuck making the same plan again?” |
| Stop teacher | “Is the old automatic robot turned off?” |
| Answer teacher | “Will the result clearly say what happened?” |

The safety teachers block words and ideas like deleting everything, destroying cloud machines, restarting everything, or saying “ignore all the rules.”

They also block a target like:

> “Fix **all** computers.”

A safe plan must point to one small, clear target.

If even one important safety teacher says **no**, the app stops the plan and tells a human helper:

> “This problem needs a careful person.”

## 7. The app makes an approval card

If the safety teachers say the suggestion is okay, the app makes an **approval card**.

The card shows the grown-up:

- What problem happened.
- Which safe tool the app suggests.
- Which machine or ticket it is for.
- What the SOP book said.
- How risky the app thinks it is.
- What safety teachers checked.
- What a future rollback instruction would be.

It also puts a special secret-looking fingerprint on the card. This is called a **hash**.

You can think of a hash like putting glitter glue around the edge of a picture. If somebody changes the picture later, the glitter border will no longer match. The app can see that it was changed.

## 8. A grown-up must choose

There are different kinds of users in the app.

| Person | What they can do |
|---|---|
| Viewer | Look at problems and information. |
| Analyst | Add problems and ask the app to make a plan. |
| Admin | Read the approval card, say yes or no, and start the pretend practice. |

Only an **Admin** can say yes to the approval card.

The Admin can choose:

> “Yes, this exact plan looks safe.”

or

> “No, do not use this plan.”

The app writes down who made the choice, when they made it, and why.

If the Admin says no, the plan is finished. Nothing happens to any machine.

## 9. The app does a pretend practice

Even after an Admin says yes, the app still does **not** touch the real machine.

First it does a pretend practice, like pretending to drive a toy car before driving a real car.

The app checks again:

- Is this still the same card the Admin approved?
- Did anybody change the special hash?
- Do the safety teachers still say okay?
- Is the action still on the small safe list?

If something changed, the app stops.

If everything matches, the app writes:

> “Pretend practice finished. No real machine was changed.”

Right now, this is the final step. The app records a **simulated resolution**. That means it practiced and saved the result, but it did not really restart, delete, change, or break anything.

## 10. The app keeps a diary that is hard to secretly change

Every important event goes into the app’s diary.

For example, it writes:

1. A problem came in.
2. A plan was made.
3. Safety teachers checked it.
4. A human asked for approval.
5. An Admin said yes or no.
6. A pretend practice happened.

Every diary page is connected to the page before it with a special secret code. This makes it much harder for someone to secretly change the story later.

So if someone asks:

> “Who said yes to this plan?”

or

> “Did the app really touch anything?”

the diary can answer.

## 11. What the old automatic robot does now

The older version of the app had a robot that could see certain problem words and try a fixed command on its own.

That old robot is now **turned off** for real actions.

It wakes up every 60 seconds only to check its work area, but it is not allowed to run the old direct-fix path. This is very important because we do not want a computer to do surprise things without a person checking first.

## 12. A very short example story

Imagine this happens:

1. A monitoring tool says, “Printer 7 in Store 4 is offline.”
2. The app checks whether it already has that exact printer problem.
3. It finds the SOP page about printer problems.
4. It suggests the tiny tool: “Clear the printer queue.”
5. The safety teachers check that it is only for Printer 7—not every printer in the whole company.
6. The app makes an approval card.
7. An Admin reads the card and says yes.
8. The app does a pretend practice.
9. The app writes in its diary: “The practice was done. No machine was changed.”

That is how the app is supposed to be helpful **and** careful.

## 13. What the app will learn to do later

Before the app can safely fix real things, more work is needed.

It needs a separate, very locked-down tool runner. That runner must understand only the approved tools, never accept surprise commands, check its own permissions, and know how to undo a tool safely.

It also needs to finish hiding each team’s SOP pages from other teams at the database/search level, connect the new approval cards to the frontend screen, and use real identity sign-in instead of the proof-of-concept role chooser.

Until then, the safest rule is:

> **The app can read, suggest, check, ask, and practice. A human stays in charge.**
