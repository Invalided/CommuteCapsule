# 数据源接口

引擎只通过 `Transit` 门面访问数据，数据源实现下面两个接口（见 `app/src/main/java/com/busnotice/commute/Transit.kt`）。

## StaticSource（必需）

| 方法 | 说明 | 不支持时 |
|---|---|---|
| `search(key, region)` | 按关键字搜线路（含方向）和站点 | 必须实现 |
| `lineDetail(lineId, region)` | 线路全部站点（顺序、坐标）和反方向线路 ID | 必须实现 |
| `nearbyStops(lat, lng, region)` | 附近站点及经过的线路 | 返回空：选站时只能搜索 |
| `stationLines(siteId, sameNameId, region)` | 某站（含对面站台）经过的线路 | 返回空 |
| `subwayDirs(lat, lng, stationName, region)` | 地铁站各方向首末班、方向编号、本站序号 | 返回空：地铁按手填间隔估算 |
| `metroStops(board, toLat, toLng, region)` | 地铁坐几站 | 返回 null |
| `metroRoute(MetroQuery)` | 地铁途经站及坐标、预计用时 | 返回 null：不显示途经站 |

## RealtimeSource（可选）

| 方法 | 说明 |
|---|---|
| `lineRealtime(RealtimeQuery)` | 驶向等车站的车辆：预计秒数、还有几站、距离 |

没有实时数据源时，引擎依次退回：本机学到的时刻表 → 按站距和间隔估算。

## 约定

- 坐标一律 WGS-84（与手机 GPS 一致），其他坐标系请在数据源中自行转换
- `region` 是数据源自己的区域编号，用户在「数据源」里填写
- 网络失败直接抛异常，引擎会捕获并降级
- 线路 / 站点 ID 由数据源自定，引擎只原样保存

## 加载

在 `app/src/extra/java/com/busnotice/commute/extra/Extras.kt` 中列出你的实现，构建时自动编入，启动时加载第一个可用的数据源。`app/src/extra/assets` 里的文件会打进 App，可用 `Transit.appContext.assets` 读取。

## 参考

- `examples/json-source`：最小模板，从 JSON 文件读线路，三步可用
- `MockSource.kt`：内置演示城市，含模拟实时来车
