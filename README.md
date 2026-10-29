# entropy-blog-backend

个人博客「熵」的后端服务：Spring Boot + MySQL + Redis，提供文章、评论、音乐、赞助、文件、站点配置与用户体系，并向前端提供 `/api/v1` JSON 接口。

## 技术栈

| 层 | 选型 |
|---|---|
| 运行时 | Java 25 |
| 框架 | Spring Boot 4.1.1（Web / Security / Validation / Mail / Cache / Data Redis） |
| 持久化 | MySQL + MyBatis-Plus（`mybatis-plus-spring-boot4-starter`、jsqlparser） |
| 缓存 / 限流 | Redis（Lettuce + commons-pool2） |
| 认证 | JWT（jjwt-api / jjwt-impl / jjwt-jackson）+ Spring Security |
| 文档 | SpringDoc OpenAPI（`/v3/api-docs`、`/swagger-ui.html`） |
| 内容处理 | jsoup（HTML 净化）、commonmark（Markdown 渲染，含 GFM 表格 / autolink / heading-anchor） |
| 其他 | Lombok、Spring Boot Test |

## 功能模块

`com.qichi.blog.modules` 下按业务垂直切分，每个模块自带 `controller / service / mapper / entity`：

`admin`（后台）、`post`（文章）、`comment`（评论）、`taxonomy`（分类与标签）、`music`（歌单）、`sponsor`（赞助方式）、`upload`（文件与图片）、`user`（注册登录 / OAuth）、`mail`（邮件验证码）、`site`（站点配置）、`feed`（RSS / sitemap）、`tool`（工具箱）、`health`（健康检查）。

## 快速开始

前置：JDK 25、Maven，以及可用的 MySQL 与 Redis 实例。

```bash
# 1. 建库
mysql -uroot -p -e "CREATE DATABASE qichi_blog DEFAULT CHARSET utf8mb4;"

# 2. 配置连接与密钥（见下一节，推荐用环境变量，不要写回仓库）

# 3. 运行
mvn spring-boot:run
```

默认监听 `8080`，接口前缀 `/api/v1`，接口文档 <http://localhost:8080/swagger-ui.html>。

## 配置

仓库里的 `application.yml` / `application-prod.yml` **只保留占位符**，真实值通过环境变量注入（`${VAR:default}` 形式，冒号后为可运行的开发默认值或空）。

| 变量 | 说明 |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | MySQL 连接 |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | Redis 连接 |
| `BLOG_JWT_SECRET` | JWT 签名密钥，**生产必须替换**（32 位以上随机串） |
| `BLOG_SITE_URL` | 站点对外地址，影响 OAuth 回跳与邮件链接 |
| `BLOG_BIND_ADDRESS` | 监听地址 |
| `BLOG_COOKIE_SECURE` / `BLOG_TRUST_FORWARDED_HEADERS` / `BLOG_CORS_ALLOWED_ORIGINS` | 反代与跨域相关 |
| `BLOG_AUTH_REGISTRATION_ENABLED` | 是否开放注册 |
| `BLOG_MAIL_*`（HOST / PORT / USERNAME / PASSWORD / FROM / FROM_NAME / SSL / STARTTLS / ENABLED） | SMTP 发信 |
| `BLOG_GITHUB_CLIENT_ID` / `_SECRET` / `_REDIRECT_URI` | GitHub 登录 |
| `BLOG_GOOGLE_CLIENT_ID` / `_SECRET` / `_REDIRECT_URI` | Google 登录 |
| `BLOG_UPLOAD_DIR` | 上传文件落盘目录（默认不提交进仓库） |

**启动自检**：`StartupSecurityCheck` 会在启动时检查「密钥是否仍为空或含 `change-me`/`password`/`secret` 之类的占位词」「站点地址是否还是 localhost」「上传扩展名白名单是否为空」等会导致一上线就出事的问题——开发环境打 `WARN` 日志，`prod` profile 下直接拒绝启动。

上传单文件上限 `300MB`（`spring.servlet.multipart.max-file-size`），上传目录 `uploads/` 已被 `.gitignore` 排除。

## 构建与测试

```bash
mvn -DskipTests package      # 产出 target/qichi-blog-server-1.0.0.jar
mvn test                     # 单元测试
java -jar target/qichi-blog-server-1.0.0.jar --spring.profiles.active=prod
```

## 目录结构

```
src/main/java/com/qichi/blog/
├── cache/      缓存封装
├── common/     通用响应体、异常处理
├── config/     Security / MyBatis-Plus / SpringDoc / 启动自检
├── modules/    业务模块（admin post comment taxonomy music sponsor upload user mail site feed tool health）
└── security/   JWT 过滤器、鉴权
src/main/resources/
├── application.yml       开发配置（密钥为占位符）
└── application-prod.yml  生产配置（全部必填环境变量）
```

## 说明

本仓库中的配置不含任何真实密钥：提交前由本地脱敏过滤器把密钥替换为 `${ENV:}` 占位符。若你要基于本项目部署，请自行生成 `BLOG_JWT_SECRET`、数据库密码与第三方 OAuth 凭据。

## License

MIT
