# How the Incident Helper App Works — Like a Story

Imagine a big shop has many little machines. There are printers, internet boxes, cash-register machines, computers, and many other things. Sometimes one of them gets sick and stops working.

This app is like a **very careful helper robot**. It helps the grown-ups find the problem, read the right instructions, and suggest a safe fix. But it is **not allowed to do big things by itself**.

## 1. Someone says, “There is a problem!”

A problem can come to the helper robot in a few ways.

A different computer system, like ServiceNow, Freshservice or Jira, can send a message. A grown-up can also upload a spreadsheet, like a CSV or Excel file, full of problems.

There is **no button for typing in a brand-new problem by hand**. That button used to exist and it was taken away on purpose. If a person types a problem into this app, that problem does not exist in the big system everybody else is watching — and the moment the two lists disagree, nobody can trust either one.

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
| Secret teacher | “Is this trying to peek at passwords or keys?” |
| Round-and-round teacher | “Is the app stuck making the same plan again?” |
| Too-long teacher | “Is this script so long that nobody will really read it?” |
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
- Which machine it is for, and which kind of computer that machine is.
- What the SOP book said, and whether the app has fixed this exact thing before.
- How risky the app thinks it is.
- What safety teachers checked.
- **The whole script, written out**, with a sentence above it saying in plain words what each line does.

It also puts a special secret-looking fingerprint on the card. This is called a **hash**.

You can think of a hash like putting glitter glue around the edge of a picture. If somebody changes the picture later, the glitter border will no longer match. The app can see that it was changed.

## 8. A grown-up must choose

There are different kinds of users in the app.

| Person | What they can do |
|---|---|
| Viewer | Look at problems and information. |
| Analyst | Ask the app to make a plan, read the approval card, say yes or no, and start the pretend practice. |
| Admin | Everything an Analyst can do — **and only an Admin may press the button that touches the real machine.** |

So two different grown-ups are usually involved: one says “this plan is safe”, and one says “now do it”. And in a real setup the app will not let the **same** person do both — the grown-up who asked for the plan is not allowed to approve their own plan.

The grown-up reading the card can choose:

> “Yes, this exact plan looks safe.”

or

> “No, do not use this plan.”

The app writes down who made the choice, when they made it, and why.

If the grown-up says no, the plan is finished. Nothing happens to any machine.

## 9. The app does a pretend practice

Even after a grown-up says yes, the app still does **not** touch the real machine.

First it does a pretend practice, like pretending to drive a toy car before driving a real car.

The app checks again:

- Is this still the same card that was approved?
- Did anybody change the special hash?
- Do the safety teachers still say okay?
- Is the action still on the small safe list?
- Can the app even reach that machine, and what kind of computer is it?

If something changed, the app stops.

If everything matches, the app writes:

> “Pretend practice finished. No real machine was changed.”

The pretend practice is **not optional**. It has to happen before anything real can.

## 9b. Then, and only then, the real fix

After the practice passes, an **Admin** can press the last button.

Here is the important part: **this app never runs the fix itself.** It has no way to. Instead it hands the script to a completely separate little helper — a **tool runner** that lives out where the machines are. That tool runner is the only one that knows the machine’s password. This app only knows who approved what.

That split is on purpose. If a bad person somehow got into this app, they would find approval cards and a diary — not the keys to every machine in every shop.

And if the grown-ups have not set up a tool runner yet, the app says so honestly: it writes down that the fix was **pretended**, not done. It never claims to have fixed something it did not fix.

## 10. The app keeps a diary that is hard to secretly change

Every important event goes into the app’s diary.

For example, it writes:

1. A problem came in.
2. A plan was made.
3. Safety teachers checked it.
4. A human was asked for approval.
5. A grown-up said yes or no, and why.
6. A pretend practice happened.
7. A real fix ran, and exactly what the machine said back.

Every diary page is connected to the page before it with a special secret code. This makes it much harder for someone to secretly change the story later.

So if someone asks:

> “Who said yes to this plan?”

or

> “Did the app really touch anything?”

the diary can answer.

## 11. What happened to the old automatic robot

The older version of the app had a robot that could see certain problem words and try a fixed command on its own. If it had fixed the same thing at the same shop before, and a person had said yes back then, it was allowed to just go and do it again.

That robot is **gone**. Not switched off — deleted, along with the switch that used to turn it on.

Here is why that matters. An app that can run by itself “only if a little setting says so” has to be checked all over again every single time somebody changes that setting. An app with no such setting only has to be checked once. So now there is exactly one road, and a person stands on it: **every fix is a grown-up reading that exact script for that exact machine and saying yes — even the hundredth time.**

One small robot does still wake up on a timer, and it is worth knowing what it does. Once an hour it goes and asks ServiceNow, Freshservice and Jira, “any new problems?” and copies them into the queue. That is all it does. It **writes problems down. It never fixes anything.**

## 12. A very short example story

Imagine this happens:

1. A monitoring tool says, “Printer 7 in Store 4 is offline.”
2. The app checks whether it already has that exact printer problem.
3. It finds the SOP page about printer problems, and it also looks back at old problems to see whether somebody fixed this before.
4. It suggests the tiny tool: “Clear the printer queue.”
5. It works out **which machine** — the one named on the ticket, not a guess — and asks that machine what kind of computer it is, so the script is written the right way.
6. The safety teachers check that it is only for Printer 7 — not every printer in the whole company.
7. The app makes an approval card, with the whole script written out and explained.
8. An Analyst reads the card and says yes. The app puts glitter glue around the script at that exact moment.
9. The app does a pretend practice and checks it can reach the machine.
10. An Admin presses the last button. The app hands the script to the tool runner out at the shop.
11. The app writes in its diary who approved it, who ran it, and exactly what the machine said back.

That is how the app is supposed to be helpful **and** careful.

## 13. What the app still needs to learn

Before this app can be trusted in a big company, some real work is still missing.

The biggest one: **the locked-down tool runner is not finished.** The app knows how to hand a script to one, and there is a pretend one for practising, but a real runner — one that understands only the approved tools, refuses surprise commands, and keeps its own diary — still has to be built. Right now that runner would also be handed the same key no matter which team’s machine it is touching, and that needs fixing before more than one team shares it.

There are also some untidy things a grown-up should fix before letting strangers near it. The app’s starting password is far too easy to guess. It writes down some secrets in its notebook when it should keep them somewhere safer. When it is talking to the clever language model, it writes the whole conversation into its log file, and those conversations have real ticket details in them. And the one-command “just start everything” recipe does not actually work yet — it forgets to give the app its secret signing key, so the app refuses to wake up.

All of those are written down properly in the public [security policy](../SECURITY.md) and [known gaps](../README.md#known-gaps).

Until they are fixed, the safest rule is:

> **The app can read, suggest, check, ask, practise, and — when a grown-up presses the button — fix one machine at a time. A human stays in charge.**
