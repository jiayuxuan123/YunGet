const MAX_IMAGE = 512 * 1024;
const MAX_STORAGE = 96 * 1024 * 1024;
const signature = bytes => String.fromCharCode(...bytes);

function join(parts) {
  const output = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) { output.set(part, offset); offset += part.length; }
  return output;
}

export function sanitizeImage(bytes) {
  const dimensions = (width, height) => {
    if (!width || !height || width > 1600 || height > 1600 || width * height > 2_560_000) throw Error('图片尺寸过大，请压缩后上传');
    return {width, height};
  };
  if (signature(bytes.slice(0, 8)) === '\x89PNG\r\n\x1a\n') {
    const keep = [bytes.slice(0, 8)]; let offset = 8, size = null, data = false, ended = false;
    while (offset + 12 <= bytes.length) {
      const view = new DataView(bytes.buffer, bytes.byteOffset + offset);
      const length = view.getUint32(0), type = signature(bytes.slice(offset + 4, offset + 8));
      if (length > bytes.length - offset - 12) throw Error('图片内容不完整');
      if (offset === 8 && (type !== 'IHDR' || length !== 13)) throw Error('PNG 文件头无效');
      if (type === 'IHDR') {
        if (size) throw Error('PNG 文件头重复');
        size = dimensions(view.getUint32(8), view.getUint32(12));
      }
      if (['IHDR', 'PLTE', 'tRNS', 'IDAT', 'IEND', 'sRGB', 'gAMA', 'cHRM'].includes(type)) keep.push(bytes.slice(offset, offset + length + 12));
      if (type === 'IDAT') data = true;
      offset += length + 12;
      if (type === 'IEND') { ended = true; break; }
    }
    if (!size || !data || !ended || offset !== bytes.length) throw Error('PNG 图片无效');
    return {bytes: join(keep), mime: 'image/png', ...size};
  }
  if (signature(bytes.slice(0, 4)) === 'RIFF' && signature(bytes.slice(8, 12)) === 'WEBP') {
    const root = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (root.getUint32(4, true) + 8 !== bytes.length) throw Error('WebP 内容不完整');
    const keep = []; let offset = 12, size = null, extended = null, images = 0;
    while (offset + 8 <= bytes.length) {
      const type = signature(bytes.slice(offset, offset + 4)), length = root.getUint32(offset + 4, true);
      const end = offset + 8 + length + (length % 2);
      if (end > bytes.length) throw Error('WebP 图片无效');
      const chunk = bytes.slice(offset, end), data = new DataView(bytes.buffer, bytes.byteOffset + offset + 8, length);
      if (type === 'VP8X') {
        if (length !== 10 || (chunk[8] & 2)) throw Error('请将动态图转换为静态图片');
        extended = dimensions(1 + data.getUint8(4) + (data.getUint8(5) << 8) + (data.getUint8(6) << 16),
          1 + data.getUint8(7) + (data.getUint8(8) << 8) + (data.getUint8(9) << 16));
        chunk[8] &= ~(0x20 | 0x08 | 0x04);
        keep.push(chunk);
      } else if (type === 'VP8L') {
        if (length < 5 || data.getUint8(0) !== 0x2f) throw Error('WebP 图片无效');
        const bits = data.getUint32(1, true);
        size = dimensions((bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1);
        images++; keep.push(chunk);
      } else if (type === 'VP8 ') {
        if (length < 10 || signature(new Uint8Array(data.buffer, data.byteOffset + 3, 3)) !== '\x9d\x01\x2a') throw Error('WebP 图片无效');
        size = dimensions(data.getUint16(6, true) & 0x3fff, data.getUint16(8, true) & 0x3fff);
        images++; keep.push(chunk);
      } else if (type === 'ALPH') keep.push(chunk);
      offset = end;
    }
    if (offset !== bytes.length || images !== 1 || !size || (extended && (extended.width !== size.width || extended.height !== size.height))) throw Error('WebP 图片无效');
    const output = join([bytes.slice(0, 12), ...keep]);
    new DataView(output.buffer).setUint32(4, output.length - 8, true);
    return {bytes: output, mime: 'image/webp', ...size};
  }
  throw Error('仅接受压缩后的 PNG 或 WebP 图片');
}

export function createMedia({fail, secureResponse, rate, stamp, uniqueKey}) {
  async function validate(env, owner, input, max) {
    if (input === undefined) return [];
    if (!Array.isArray(input) || input.length > max || new Set(input).size !== input.length || input.some(x => !/^m_[a-f0-9]{32}$/.test(x))) fail(422, `最多添加 ${max} 张图片`);
    if (!input.length) return [];
    const marks = input.map(() => '?').join(',');
    const rows = (await env.DB.prepare(`SELECT m.id FROM community_media m LEFT JOIN community_attachments a ON a.media_id=m.id WHERE m.id IN (${marks}) AND m.owner_id=? AND a.media_id IS NULL`).bind(...input, owner.id_hash).all()).results;
    if (rows.length !== input.length) fail(403, '图片无效、已发布或不属于当前账号');
    return input;
  }
  function attach(env, owner, ids, postId, commentId = null) {
    return ids.map(mediaId => env.DB.prepare('INSERT INTO community_attachments(media_id,post_id,comment_id) SELECT id,?,? FROM community_media WHERE id=? AND owner_id=?')
      .bind(postId, commentId, mediaId, owner.id_hash));
  }
  async function forComments(env, ids, admin) {
    if (!ids.length) return [];
    const rows = (await env.DB.prepare(`SELECT a.comment_id,m.id,m.width,m.height FROM community_attachments a JOIN community_media m ON m.id=a.media_id WHERE a.comment_id IN (${ids.map(() => '?').join(',')}) ORDER BY m.created_at,m.id`).bind(...ids).all()).results;
    return rows.map(row => ({...row, url: `${admin ? '/admin' : ''}/media/${row.id}`}));
  }
  async function handle(request, env, {owner, admin, path}) {
    if (path === '/api/media' && request.method === 'POST') {
      if (!owner) fail(401, '请先建立社区身份');
      const key = uniqueKey(request);
      const old = await env.DB.prepare('SELECT id,width,height FROM community_media WHERE owner_id=? AND upload_key=?').bind(owner.id_hash, key).first();
      if (old) {
        await request.body?.cancel();
        return secureResponse({id: old.id, url: `${admin ? '/admin' : ''}/media/${old.id}`, width: old.width, height: old.height});
      }
      await rate(request, env, 'image', 12, 600);
      if (!['image/webp', 'image/png'].includes(request.headers.get('Content-Type')?.split(';')[0])) fail(415, '请使用选图按钮上传图片');
      const reader = request.body?.getReader();
      if (!reader) fail(422, '图片内容为空');
      const chunks = []; let total = 0;
      while (true) {
        const {done, value} = await reader.read(); if (done) break;
        total += value.byteLength;
        if (total > MAX_IMAGE) { await reader.cancel(); fail(413, '图片过大，请压缩至 512 KiB 以内'); }
        chunks.push(value);
      }
      let image;
      try { image = sanitizeImage(join(chunks)); } catch (error) { fail(422, error.message); }
      const bytes = image.bytes, imageId = 'm_' + crypto.randomUUID().replaceAll('-', '');
      const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(x => x.toString(16).padStart(2, '0')).join('');
      const result = await env.DB.prepare('INSERT INTO community_media(id,owner_id,mime,size,width,height,digest,upload_key,data,created_at) SELECT ?,?,?,?,?,?,?,?,?,? WHERE (SELECT COALESCE(SUM(size),0) FROM community_media)+?<=? AND (SELECT COUNT(*) FROM community_media WHERE owner_id=? AND created_at>?)<20 AND (SELECT COUNT(*) FROM community_media m LEFT JOIN community_attachments a ON a.media_id=m.id WHERE m.owner_id=? AND a.media_id IS NULL)<8 ON CONFLICT(owner_id,upload_key) DO NOTHING')
        .bind(imageId, owner.id_hash, image.mime, bytes.length, image.width, image.height, digest, key, bytes.buffer, stamp(), bytes.length, MAX_STORAGE, owner.id_hash, stamp() - 86400, owner.id_hash).run();
      if (!result.meta.changes) {
        const concurrent = await env.DB.prepare('SELECT id,width,height FROM community_media WHERE owner_id=? AND upload_key=?').bind(owner.id_hash, key).first();
        if (concurrent) return secureResponse({id: concurrent.id, url: `${admin ? '/admin' : ''}/media/${concurrent.id}`, width: concurrent.width, height: concurrent.height});
      }
      if (!result.meta.changes) fail(429, '图片额度暂不可用，可清理未发布图片或稍后再试；仍可发布文字');
      return secureResponse({id: imageId, url: `${admin ? '/admin' : ''}/media/${imageId}`, width: image.width, height: image.height}, 201);
    }
    const match = /^\/media\/(m_[a-f0-9]{32})$/.exec(path);
    if (!match) return null;
    const imageId = match[1];
    if (request.method === 'DELETE') {
      if (!owner) fail(401, '请先建立社区身份');
      await env.DB.prepare('DELETE FROM community_media WHERE id=? AND owner_id=? AND NOT EXISTS(SELECT 1 FROM community_attachments a WHERE a.media_id=community_media.id)').bind(imageId, owner.id_hash).run();
      return secureResponse({ok: true});
    }
    if (request.method !== 'GET') return null;
    const row = await env.DB.prepare('SELECT m.owner_id,m.mime,m.digest,m.data,a.post_id,p.hidden,c.hidden AS comment_hidden FROM community_media m LEFT JOIN community_attachments a ON a.media_id=m.id LEFT JOIN community_posts p ON p.id=a.post_id LEFT JOIN community_comments c ON c.id=a.comment_id WHERE m.id=?').bind(imageId).first();
    if (!row) fail(404, '图片不存在');
    const isPublic = row.post_id && row.hidden === 0 && (row.comment_hidden === null || row.comment_hidden === 0);
    if (!isPublic && !admin && row.owner_id !== owner?.id_hash) fail(404, '图片不可见');
    const headers = new Headers(secureResponse({}).headers);
    headers.set('Content-Type', row.mime);
    headers.set('Content-Disposition', 'inline; filename="community-image.' + (row.mime === 'image/png' ? 'png' : 'webp') + '"');
    headers.set('Cache-Control', isPublic ? 'public,max-age=300' : 'private,no-store');
    headers.set('ETag', '"' + row.digest + '"');
    if (request.headers.get('If-None-Match') === headers.get('ETag')) return new Response(null, {status: 304, headers});
    const bytes = row.data instanceof ArrayBuffer ? new Uint8Array(row.data) : new Uint8Array(row.data);
    return new Response(bytes, {headers});
  }
  return {validate, attach, forComments, handle};
}
