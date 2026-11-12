export function createInbox({fail, readJson, secureResponse, stamp}) {
  async function handle(request, env, {owner, path}) {
    if (!path.startsWith('/api/notifications')) return null;
    const url = new URL(request.url);
    if (request.method === 'GET') {
      const account = owner?.id_hash || '';
      const counts = await env.DB.batch([
        env.DB.prepare('SELECT COUNT(*) AS count,COALESCE(MAX(id),0) AS latest FROM community_notifications WHERE account_id=? AND read_at IS NULL').bind(account),
        env.DB.prepare('SELECT a.id,a.title,a.body,a.date,a.updated_at FROM announcements a LEFT JOIN community_announcement_reads r ON r.account_id=? AND r.announcement_id=a.id WHERE a.published=1 AND (r.revision IS NULL OR r.revision<a.updated_at) ORDER BY a.pinned DESC,a.date DESC,a.id DESC LIMIT 20').bind(account),
      ]);
      const unread = counts[0].results[0].count + counts[1].results.length;
      if (path === '/api/notifications/unread') return secureResponse({unread: owner ? unread : 0, announcements: counts[1].results.length});
      if (path !== '/api/notifications') return null;
      const before = Number(url.searchParams.get('before') || Number.MAX_SAFE_INTEGER);
      if (!Number.isSafeInteger(before) || before <= 0) fail(422, '分页位置无效');
      const rows = (await env.DB.prepare('SELECT n.id,n.kind,n.post_id,n.comment_id,n.feedback_id,n.preview,n.created_at,n.read_at,a.uid,a.nickname FROM community_notifications n LEFT JOIN community_accounts a ON a.id=n.actor_id WHERE n.account_id=? AND n.id<? ORDER BY n.id DESC LIMIT 31').bind(account, before).all()).results;
      const page = rows.slice(0, 30);
      return secureResponse({items: page.map(row => ({id: row.id, kind: row.kind, postId: row.post_id, commentId: row.comment_id,
        feedbackId: row.feedback_id, preview: row.preview, createdAt: row.created_at, read: row.read_at !== null,
        actor: row.uid ? {uid: row.uid, nickname: row.nickname} : null})),
        announcements: counts[1].results.map(row => ({id: row.id, title: row.title, body: row.body, date: row.date, revision: row.updated_at})),
        unread, throughId: counts[0].results[0].latest, next: rows.length > 30 ? page.at(-1).id : null});
    }
    if (request.method === 'POST' && path === '/api/notifications/read') {
      if (!owner) fail(401, '请先建立社区身份');
      const body = await readJson(request), through = body.throughId || 0;
      if (!Number.isSafeInteger(through) || through < 0) fail(422, '通知位置无效');
      const ids = body.ids || [];
      if (!Array.isArray(ids) || ids.length > 100 || ids.some(x => !Number.isSafeInteger(x) || x <= 0)) fail(422, '通知记录无效');
      const announcements = body.announcements || [];
      if (!Array.isArray(announcements) || announcements.length > 20 || announcements.some(x => typeof x.id !== 'string' || x.id.length > 100 || !Number.isSafeInteger(x.revision))) fail(422, '公告记录无效');
      await env.DB.batch([
        env.DB.prepare('UPDATE community_notifications SET read_at=? WHERE account_id=? AND id<=? AND read_at IS NULL').bind(stamp(), owner.id_hash, through),
        ...(ids.length ? [env.DB.prepare(`UPDATE community_notifications SET read_at=? WHERE account_id=? AND id IN (${ids.map(() => '?').join(',')}) AND read_at IS NULL`).bind(stamp(), owner.id_hash, ...ids)] : []),
        ...announcements.map(item => env.DB.prepare('INSERT INTO community_announcement_reads(account_id,announcement_id,revision) SELECT ?,id,updated_at FROM announcements WHERE id=? AND updated_at=? AND published=1 ON CONFLICT(account_id,announcement_id) DO UPDATE SET revision=MAX(revision,excluded.revision)').bind(owner.id_hash, item.id, item.revision)),
      ]);
      return secureResponse({ok: true});
    }
    return null;
  }
  return {handle};
}
