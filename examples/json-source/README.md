# 数据源模板：本地 JSON

最简单的接入方式：把线路和站点写进一个 JSON 文件，编进 App。

## 三步用起来

1. 把本目录复制成 `app/src/extra`：
   ```bash
   cp -r examples/json-source app/src/extra
   ```
2. 改 `app/src/extra/assets/transit.json`，填你自己的线路。每条线路一个方向，往返写两条，用 `reverseId` 互相指向：
   - `id`：线路编号，自定义，须唯一
   - `name`：显示名（公交写数字，App 会自动加「路」）
   - `subway`：地铁线写 `true`，可省略
   - `stops`：按行驶顺序的站点，坐标用 WGS-84（手机 GPS 的坐标）
3. 推送或本地编译。`app/src/extra` 存在时会自动编进去，菜单「数据源」里能看到「本地 JSON」。

## 能做和不能做

- 能：搜线路、搜站点、看附近站点、选上下车站、按学到的时刻表和站距估算来车
- 不能：实时来车。需要的话，实现 `RealtimeSource` 并加进 `Extras.sources`，接口说明见 [docs/DATA_SOURCE.md](../../docs/DATA_SOURCE.md)

示例里的站名和坐标是虚构的（坐标在海上）。
