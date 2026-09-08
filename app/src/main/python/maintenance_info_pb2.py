"""游戏维护信息接口的 protobuf 消息定义。

消息布局（字段名与编号）由游戏客户端实际使用的线上协议决定，属于接口
事实，不能改；实现上不用 protoc 生成，而是运行时构造 descriptor 注册
进独立 pool。App 里真正消费的只有 MaintenanceInfoResponse 的解析
（market_info.bundle_version / bundle_version_sd）。
"""
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory

_F = descriptor_pb2.FieldDescriptorProto
_INT32, _STRING, _BOOL = _F.TYPE_INT32, _F.TYPE_STRING, _F.TYPE_BOOL


def _field(name, number, ftype, type_name=None):
    f = _F()
    f.name = name
    f.number = number
    f.type = ftype
    f.label = _F.LABEL_OPTIONAL
    if type_name:
        f.type_name = type_name
    return f


def _message(name, fields):
    m = descriptor_pb2.DescriptorProto()
    m.name = name
    m.field.extend(fields)
    return m


schema = descriptor_pb2.FileDescriptorProto()
schema.name = "maintenance_info.proto"
schema.syntax = "proto3"
schema.message_type.extend([
    _message("MaintenanceInfo", [
        _field("market_type", 1, _INT32),
        _field("version", 2, _STRING),
        _field("bundle_version", 3, _STRING),
        _field("is_bundle_update", 4, _BOOL),
        _field("maintenance_type", 5, _INT32),
        _field("date", 6, _STRING),
        _field("region_list", 7, _STRING),
        _field("use_dsa", 8, _BOOL),
        _field("maintenance_url", 9, _STRING),
        _field("use_maintenance_url", 10, _BOOL),
        _field("download_url", 11, _STRING),
        _field("notice", 12, _STRING),
        _field("bundle_version_sd", 13, _STRING),
    ]),
    _message("MaintenanceInfoRequest", [
        _field("seq", 1, _INT32),
        _field("market_type", 2, _INT32),
        _field("version", 3, _STRING),
        _field("bundle_version", 4, _STRING),
        _field("access_token", 5, _STRING),
        _field("language_type", 6, _INT32),
    ]),
    _message("MaintenanceInfoResponse", [
        _field("market_info", 1, _F.TYPE_MESSAGE, type_name=".MaintenanceInfo"),
        _field("server_connect_info", 2, _STRING),
        _field("connect_type", 3, _INT32),
        _field("user_type", 4, _INT32),
    ]),
])

_pool = descriptor_pool.DescriptorPool()
_pool.Add(schema)

MaintenanceInfo = message_factory.GetMessageClass(_pool.FindMessageTypeByName("MaintenanceInfo"))
MaintenanceInfoRequest = message_factory.GetMessageClass(_pool.FindMessageTypeByName("MaintenanceInfoRequest"))
MaintenanceInfoResponse = message_factory.GetMessageClass(_pool.FindMessageTypeByName("MaintenanceInfoResponse"))
