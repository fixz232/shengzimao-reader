# 漫源榜单与分类（discover 规则）

从 1.10.0 起，漫画源可以声明一个**可选**的 `discover` 段，用来提供排行榜与分类入口。
长按「在线搜漫」页面里的某个漫源，即可进入该源的详情页看到这些入口。

> 没有 `discover` 段的源照常可用：详情页会退回「分类关键词搜索」，
> 并在界面上明确标注**这不是源站官方榜单**。

---

## 1. 规则格式

```json
{
  "id": "example",
  "name": "示例漫画",
  "baseUrl": "https://example.com",

  "search": { "url": "/search?q={query}", "items": ".list li", "title": "a@title", "link": "a@href" },
  "detail": { "chapters": ".chapter-list a", "chapterUrl": "@href", "chapterTitle": "@title" },
  "reader": { "pages": ".reader img" },

  "discover": {
    "groups": [
      {
        "name": "排行榜",
        "entries": [
          { "name": "人气榜", "url": "/rank/popular", "page": "?page={page}" },
          { "name": "新作榜", "url": "/rank/new" }
        ]
      },
      {
        "name": "分类",
        "entries": [
          { "name": "热血", "url": "/tag/action" },
          { "name": "恋爱", "url": "/tag/romance" }
        ]
      }
    ],
    "items": ".comic-list li",
    "title": "a@title",
    "link": "a@href",
    "cover": "img@src",
    "author": ".author"
  }
}
```

### 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `groups` | 是 | 分组列表，最多 12 组。每组有 `name` 与 `entries` |
| `groups[].entries[].name` | 是 | 显示名，最多 60 字 |
| `groups[].entries[].url` | 是 | 相对 `baseUrl` 的路径，支持 `{page}` 占位 |
| `groups[].entries[].page` | 否 | 翻页后缀，支持 `{page}`。留空则不支持翻页 |
| `items` | 是 | 条目选择器，语法与 `search.items` 完全相同 |
| `title` | 是 | 标题选择器，语法与 `search.title` 相同 |
| `link` | 是 | 详情链接选择器，语法与 `search.link` 相同 |
| `cover` | 否 | 封面选择器 |
| `author` | 否 | 作者选择器 |

`items` / `title` / `link` 复用 search 段的解析器，因此支持同样的写法：
CSS 选择器、`@href` / `@title` / `@src` 属性、以及 `{page}` 之类的占位替换。

---

## 2. 解析器的容错策略

`discover` 是**增强**能力，配置错误绝不能让整个源不可用：

- 缺少 `discover` 段 → 返回 null，界面走关键词降级
- 缺少 `items` / `title` / `link` 任一 → 返回 null，走降级
- `groups` 为空 → 返回 null，走降级
- 单个条目的 `name` 或 `url` 为空 → **只丢弃该条目**，其余保留
- 解析过程中任何异常 → 捕获并返回 null

以上行为都有单元测试覆盖（`MangaDiscoverTest`）。

---

## 3. 降级方案（源未声明 discover）

详情页会展示以下关键词作为「分类」入口，点击后调用该源自己的**搜索**：

```
排行 / 热门 / 已完结 / 连载中 / 热血 / 恋爱 / 搞笑 / 奇幻 / 悬疑 / 校园
```

界面上会显示提示：

> 该源没有配置 discover 榜单规则，下面的分类是「关键词搜索」，不是源站官方排行榜。

这样用户不会误以为看到了官方榜单。

---

## 4. 已知限制

- **不属于官方榜单**：即使配置了 `discover`，抓取到的也只是该 URL 页面当时的内容，
  顺序与内容随源站变化，本应用不做任何排序或过滤（除了去重）。
  这是刻意的：自行重排会让「人气榜第 3 名」这种说法失去意义。
- **不支持需要登录/付费/验证的分类页**：与搜索一致。
- **翻页语义由源站决定**：`page` 后缀只是拼接，不保证源站真的支持。
  如果源站忽略该参数，你会看到重复结果（已按 URL 去重）。
- **旧式（legacy）源**：走的是脚本引擎，`discover` 段对它们不生效，
  详情页会显示「无榜单」并走关键词降级。
- 本应用**不内置任何漫画源**。上面的 `example.com` 只是格式示意，
  你需要自己准备有权访问的源。
