export const analyticsEvents = new Set([
  'app_open', 'resolve_start', 'resolve_success', 'resolve_fail',
  'download_queued', 'download_started', 'download_resumed', 'download_completed',
  'download_failed', 'download_cancelled', 'save_failed', 'processing_failed',
  'qr_scan_success', 'theme_changed',
]);

const words = values => new Set(values.split(' '));
const properties = {
  source: words('unknown other direct quark uc xunlei baidu pan123 c139 pan115 guangya ilanzou lanzou feijipan ctfile github douyin weibo xiaohongshu bilibili kuaishou acfun pixiv'),
  engine: words('turbodl legacy aria2 gopeed other unknown'),
  error: words('unknown auth gone rate_limited checksum storage timeout cancelled network other tls range short_read html permission processing http_401 http_403 http_404 http_410'),
  kind: words('unknown share command github direct video other'),
  result: words('success failed cancelled unknown'),
  abi: new Set(['arm64' + '-v8a', 'armeabi' + '-v7a', 'x86', 'x86_64', 'unknown']),
  size_bucket: words('unknown <1MiB 1-10MiB 10-50MiB 50-100MiB 100-512MiB 512MiB-1GiB >=1GiB'),
  duration_bucket: words('unknown <5s 5-30s 30-120s 2-10m 10-60m >=1h'),
  connections_bucket: words('unknown 1 2-4 5-8 9-16 17-32 33-64 65-128 129-256'),
  ui_style: words('classic modern'),
  theme: words('light dark system'),
  file_kind: words('apk video audio image archive other unknown'),
  stage: words('unknown queued probing transferring verifying finalizing network processing saving save_failed saved'),
};

export function analyticsProperties(value) {
  if (value === undefined) return '{}';
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('统计属性无效');
  const entries = Object.entries(value);
  if (entries.length > 20) throw new Error('统计属性过多');
  const clean = Object.create(null);
  for (const [key, raw] of entries) {
    if (key === 'api' && Number.isInteger(raw) && raw >= 24 && raw <= 100) clean[key] = raw;
    else if (key === 'schema_version' && raw === 2) clean[key] = raw;
    else if (key === 'http_status' && Number.isInteger(raw) && raw >= 400 && raw <= 699) clean[key] = raw;
    else if (key === 'occurrences' && Number.isInteger(raw) && raw >= 1 && raw <= 1000) clean[key] = raw;
    else if (key === 'is_guest' && typeof raw === 'boolean') clean[key] = raw;
    else if (Object.hasOwn(properties, key) && typeof raw === 'string' && properties[key].has(raw)) clean[key] = raw;
    else throw new Error('统计属性值无效');
  }
  return JSON.stringify(clean);
}

export async function readAnalytics(db, {days = 7, version, now = Math.floor(Date.now() / 1000)} = {}) {
  const offset = 8 * 3600;
  const since = Math.floor((now + offset) / 86400) * 86400 - offset - (days - 1) * 86400;
  const weight = "COALESCE(json_extract(properties,'$.occurrences'),1)";
  const clause = 'created_at>=? AND created_at<=?' + (version ? ' AND app_version=?' : '');
  const params = version ? [since, now, version] : [since, now];
  const all = async sql => (await db.prepare(sql).bind(...params).all()).results;
  const [summaryRow, sparseDays, versions, events, errors, engines, sources] = await Promise.all([
    db.prepare(`SELECT COALESCE(SUM(${weight}),0) AS events,COUNT(DISTINCT install_hash) AS installs,
      COALESCE(SUM(CASE WHEN event='app_open' THEN ${weight} ELSE 0 END),0) AS app_opens,
      COALESCE(SUM(CASE WHEN event='resolve_start' THEN ${weight} ELSE 0 END),0) AS resolve_starts,
      COALESCE(SUM(CASE WHEN event='resolve_success' THEN ${weight} ELSE 0 END),0) AS resolve_successes,
      COALESCE(SUM(CASE WHEN event='resolve_fail' THEN ${weight} ELSE 0 END),0) AS resolve_failures,
      COALESCE(SUM(CASE WHEN event='download_started' THEN ${weight} ELSE 0 END),0) AS download_starts,
      COALESCE(SUM(CASE WHEN event='download_resumed' THEN ${weight} ELSE 0 END),0) AS download_resumes,
      COALESCE(SUM(CASE WHEN event='download_completed' THEN ${weight} ELSE 0 END),0) AS download_successes,
      COALESCE(SUM(CASE WHEN event='download_failed' AND COALESCE(json_extract(properties,'$.error'),'')<>'cancelled' THEN ${weight} ELSE 0 END),0) AS download_failures,
      COALESCE(SUM(CASE WHEN event='download_cancelled' OR (event='download_failed' AND json_extract(properties,'$.error')='cancelled') THEN ${weight} ELSE 0 END),0) AS cancellations,
      COALESCE(SUM(CASE WHEN event='save_failed' THEN ${weight} ELSE 0 END),0) AS save_failures,
      COALESCE(SUM(CASE WHEN event='processing_failed' THEN ${weight} ELSE 0 END),0) AS processing_failures,
      COALESCE(SUM(CASE WHEN json_extract(properties,'$.schema_version')=2 THEN ${weight} ELSE 0 END),0) AS v2_events
      FROM analytics_events WHERE ${clause}`).bind(...params).first(),
    all(`SELECT date(created_at,'unixepoch','+8 hours') AS day, COUNT(DISTINCT install_hash) AS installs,SUM(${weight}) AS count
      FROM analytics_events WHERE ${clause} GROUP BY day ORDER BY day`),
    all(`SELECT app_version,COUNT(DISTINCT install_hash) AS installs,SUM(${weight}) AS count
      FROM analytics_events WHERE ${clause} GROUP BY app_version ORDER BY installs DESC LIMIT 20`),
    all(`SELECT event,SUM(${weight}) AS count FROM analytics_events WHERE ${clause} GROUP BY event ORDER BY count DESC`),
    all(`SELECT event,COALESCE(json_extract(properties,'$.source'),'unknown') AS source,
      COALESCE(json_extract(properties,'$.engine'),'unknown') AS engine,
      COALESCE(json_extract(properties,'$.error'),'unknown') AS error,
      json_extract(properties,'$.http_status') AS http_status,
      COALESCE(json_extract(properties,'$.stage'),'unknown') AS stage,
      SUM(${weight}) AS count,COUNT(DISTINCT install_hash) AS installs
      FROM analytics_events WHERE ${clause} AND event IN ('resolve_fail','download_failed','save_failed','processing_failed')
      AND COALESCE(json_extract(properties,'$.error'),'')<>'cancelled'
      GROUP BY event,source,engine,error,http_status,stage ORDER BY count DESC LIMIT 60`),
    all(`SELECT COALESCE(json_extract(properties,'$.engine'),'unknown') AS engine,SUM(${weight}) AS count,
      COUNT(DISTINCT install_hash) AS installs FROM analytics_events WHERE ${clause}
      AND event IN ('download_queued','download_started','download_resumed')
      AND json_extract(properties,'$.engine') IS NOT NULL GROUP BY engine ORDER BY installs DESC LIMIT 12`),
    all(`SELECT COALESCE(json_extract(properties,'$.source'),'unknown') AS source,event,
      SUM(${weight}) AS count,COUNT(DISTINCT install_hash) AS installs FROM analytics_events WHERE ${clause}
      AND event IN ('download_completed','download_failed','resolve_fail','save_failed','processing_failed')
      GROUP BY source,event ORDER BY count DESC LIMIT 60`),
  ]);
  const summary = Object.fromEntries(Object.entries(summaryRow || {}).map(([key, value]) => [key, Number(value || 0)]));
  summary.download_outcomes = summary.download_successes + summary.download_failures;
  summary.download_completion_share = summary.download_outcomes > 0 ? summary.download_successes / summary.download_outcomes : null;
  const dayMap = new Map(sparseDays.map(row => [row.day, row]));
  const daily = Array.from({length: days}, (_, i) => {
    const day = new Date((since + i * 86400 + offset) * 1000).toISOString().slice(0, 10);
    return dayMap.get(day) || {day, installs: 0, count: 0};
  });
  return {
    days, version: version || null, timezone: 'Asia/Shanghai', summary, daily, versions, events, errors, engines, sources,
    methodology: '完成占比按已上报的完成/失败结果计算，排除取消；它不是文件下载成功率。相邻且属性相同的匿名事件会合并为计数，原始匿名事件仅保留最近 7 天，统计按接收时间归日。2.7.31 缺少来源及精确错误码，未知项不补造。',
  };
}
