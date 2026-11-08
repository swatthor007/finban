import { test, before, after } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc } from 'firebase/firestore';

let env;
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-guard',
    firestore: { rules: readFileSync('../firestore.rules', 'utf8'), host: '127.0.0.1', port: 8080 } });
  await env.withSecurityRulesDisabled(async (c) => {
    const db = c.firestore();
    await setDoc(doc(db, 'links/e_c'), { elder_uid: 'e', caretaker_uid: 'c' });
    await setDoc(doc(db, 'links/e_c2'), { elder_uid: 'e', caretaker_uid: 'c2' });
    await setDoc(doc(db, 'alerts/a1'), { elder_uid: 'e', caretaker_uids: ['c'], trigger: 'manual', severity: 'high', status: 'new' });
  });
});
after(() => env.cleanup());
const db = (uid) => env.authenticatedContext(uid).firestore();
const newAlert = (o = {}) => ({ elder_uid: 'e', caretaker_uids: ['c'], trigger: 'pause_yes', severity: 'high', status: 'new', ...o });

test('users: own doc only, fcm_tokens owner-only', async () => {
  await assertSucceeds(setDoc(doc(db('e'), 'users/e'), { role: 'elder', fcm_tokens: ['t1'] }));
  await assertFails(updateDoc(doc(db('x'), 'users/e'), { fcm_tokens: ['evil'] }));
  await assertFails(updateDoc(doc(db('c'), 'users/e'), { fcm_tokens: ['evil'] }));
  await assertFails(getDoc(doc(db('x'), 'users/e')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'users/e')));
});
test('links: members read, nobody writes', async () => {
  await assertSucceeds(getDoc(doc(db('c'), 'links/e_c')));
  await assertSucceeds(getDoc(doc(db('e'), 'links/e_c')));
  await assertFails(getDoc(doc(db('x'), 'links/e_c')));
  await assertFails(getDoc(doc(db('c2'), 'links/e_c')));
  await assertFails(setDoc(doc(db('e'), 'links/e_c3'), { elder_uid: 'e', caretaker_uid: 'c3' }));
});
test('pair_codes: server only', async () => {
  await assertFails(getDoc(doc(db('e'), 'pair_codes/123456')));
  await assertFails(setDoc(doc(db('e'), 'pair_codes/123456'), {}));
});
test('alerts: elder creates own only, valid trigger/status', async () => {
  await assertSucceeds(setDoc(doc(db('e'), 'alerts/n1'), newAlert()));
  await assertFails(setDoc(doc(db('x'), 'alerts/n2'), newAlert()));
  await assertFails(setDoc(doc(db('e'), 'alerts/n3'), newAlert({ trigger: 'bogus' })));
  await assertFails(setDoc(doc(db('e'), 'alerts/n4'), newAlert({ status: 'resolved' })));
  await assertFails(setDoc(doc(db('e'), 'alerts/n5'), newAlert({ caretaker_uids: ['a', 'b', 'c', 'd'] })));
  await assertFails(setDoc(doc(db('e'), 'alerts/n6'), newAlert({ severity: 'low' })));
  await assertFails(setDoc(doc(db('e'), 'alerts/n7'), { elder_uid: 'e', caretaker_uids: ['c'], trigger: 'manual', status: 'new' }));
});
test('alerts: caretaker_uids must all be linked to the elder', async () => {
  await assertSucceeds(setDoc(doc(db('e'), 'alerts/l1'), newAlert({ caretaker_uids: ['c', 'c2'] })));
  await assertSucceeds(setDoc(doc(db('e'), 'alerts/l0'), newAlert({ caretaker_uids: [] })));
  await assertFails(setDoc(doc(db('e'), 'alerts/l2'), newAlert({ caretaker_uids: ['stranger'] })));
  await assertFails(setDoc(doc(db('e'), 'alerts/l3'), newAlert({ caretaker_uids: ['c', 'stranger'] })));
});
test('alerts: read by elder and caretaker only', async () => {
  await assertSucceeds(getDoc(doc(db('e'), 'alerts/a1')));
  await assertSucceeds(getDoc(doc(db('c'), 'alerts/a1')));
  await assertFails(getDoc(doc(db('x'), 'alerts/a1')));
});
test('alerts: caretaker updates status fields only', async () => {
  await assertSucceeds(updateDoc(doc(db('c'), 'alerts/a1'), { status: 'calling', ack_by: 'c' }));
  await assertFails(updateDoc(doc(db('c'), 'alerts/a1'), { trigger: 'manual2' }));
  await assertFails(updateDoc(doc(db('c'), 'alerts/a1'), { status: 'new' }));
  await assertFails(updateDoc(doc(db('e'), 'alerts/a1'), { status: 'seen' }));
});
test('alerts: unlisted caretaker cannot update; no deletes', async () => {
  await assertFails(updateDoc(doc(db('c2'), 'alerts/a1'), { status: 'seen' }));
  await assertFails(updateDoc(doc(db('c'), 'alerts/a1'), { status: 'seen', severity: 'none' }));
  await assertFails(deleteDoc(doc(db('e'), 'alerts/a1')));
  await assertFails(deleteDoc(doc(db('c'), 'alerts/a1')));
});
