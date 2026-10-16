import { Link } from 'react-router-dom'
import { useHasPerm } from '@/components/glass/RequireRole'
import StatusTag from '@/components/glass/StatusTag'
import { ARTICLE_TYPE_MAP, type ArticleVO } from '@/api/article'

export default function ArticleDocument({ article, author }: { article: ArticleVO; author: string }) {
  const hasPerm = useHasPerm()
  return <article className="records-paper"><h2>{article.title}</h2><div className="records-paper-meta"><StatusTag status={article.status} />　{ARTICLE_TYPE_MAP[article.articleType] ?? article.articleType} / {author}{article.publishTime && ` / ${article.publishTime.slice(0, 10)}`}</div>
    {article.coverUrl && <img src={article.coverUrl} alt="推文封面" />}
    {article.summary && <p className="records-note">{article.summary}</p>}
    {article.imageUrls?.map((url, i) => <img key={url + i} src={url} alt={`推文长图 ${i + 1}`} />)}
    {article.content && <div className="records-paper-content">{article.content}</div>}
    {!article.content && !article.imageUrls?.length && <p className="records-note">暂无正文或长图，请补充稿件内容。</p>}
    {article.reviewComment && <div className="records-related">审核意见：{article.reviewComment}</div>}
    {article.status === 'PUBLISHED' && <div className="records-related"><span>阅读 {article.readCount ?? 0} / 在看 {article.likeCount ?? 0}</span>{article.wxUrl && /^https?:\/\//i.test(article.wxUrl) && <a href={article.wxUrl} target="_blank" rel="noreferrer">查看发布链接</a>}</div>}
    {article.activityId != null && <div className="records-related"><Link to={`/activities/${article.activityId}`}>{article.activityName || '查看关联活动'}</Link>{hasPerm('material:view') && <Link to={`/archive/materials?activityId=${article.activityId}`}>查看活动材料</Link>}</div>}
  </article>
}
