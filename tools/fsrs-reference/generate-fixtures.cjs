// Independent oracle: run the unmodified published ts-fsrs@5.4.2 package.
// Download https://registry.npmjs.org/ts-fsrs/-/ts-fsrs-5.4.2.tgz and extract into package/ first.
const { fsrs, createEmptyCard, State, Rating } = require('./package/dist/index.cjs');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const scheduler = fsrs({ request_retention: 0.9, enable_fuzz: false, enable_short_term: false });
const base = Date.parse('2026-09-08T23:59:00Z');
const day = 86400000;
const rows = ['name,inputStability,inputDifficulty,lastReviewAt,repetitions,lapses,rating,now,stability,difficulty,dueAt,nextRepetitions,nextLapses'];
function sample(name, card, at, grade) {
  const next = scheduler.next(card, new Date(at), grade).card;
  rows.push([name, card.stability, card.difficulty, card.last_review?.getTime() ?? '', card.reps, card.lapses,
    ['AGAIN','HARD','GOOD','EASY'][grade-1], at, next.stability, next.difficulty, next.due.getTime(), next.reps, next.lapses].join(','));
  return next;
}
for (let grade = 1; grade <= 4; grade++) sample('new-'+grade, createEmptyCard(new Date(base)), base, grade);
for (const [name, stability, difficulty, gap] of [
  ['same-day', 2.3065, 2.11810397, 0], ['utc-midnight', 2.3065, 2.11810397, 60000],
  ['one-day', 2.3065, 2.11810397, day], ['overdue', 12.3, 8.1, 365*day],
  ['difficult-min', 0.001, 10, 10*day], ['easy-max', 36500, 1, 36500*day],
]) {
  const card = { ...createEmptyCard(new Date(base)), stability, difficulty, last_review: new Date(base), reps: 7, lapses: 2, state: State.Review };
  for (let grade = 1; grade <= 4; grade++) sample(name+'-'+grade, card, base+gap, grade);
}
let card = createEmptyCard(new Date(base));
let at = base;
for (const [index, grade] of [3,3,2,1,3,4,3,3].entries()) {
  card = sample('trace-'+index, card, at, grade);
  at = card.due.getTime();
}
const output = path.resolve(__dirname, '../../app/src/test/resources/fsrs-5.4.2-reference.csv');
fs.mkdirSync(path.dirname(output), {recursive:true});
fs.writeFileSync(output, rows.join('\n')+'\n');
console.log(`Wrote ${rows.length-1} oracle cases to ${output}`);
console.log('Package SHA256:', crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname,'ts-fsrs-5.4.2.tgz'))).digest('hex'));
