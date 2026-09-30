import struct
import sys
import win32api
import win32con

RT_ICON = 3
RT_GROUP_ICON = 14


def add_icon(exe_path, ico_path):
    with open(ico_path, 'rb') as f:
        data = f.read()

    reserved, type_, count = struct.unpack('<HHH', data[0:6])
    entries = []
    offset = 6
    for i in range(count):
        w, h, colors, res, planes, bitcount, bytes_in_res, image_offset = struct.unpack(
            '<BBBBHHII', data[offset:offset + 16])
        entries.append((w, h, colors, res, planes, bitcount, bytes_in_res, image_offset))
        offset += 16

    handle = win32api.BeginUpdateResource(exe_path, False)

    grp_data = struct.pack('<HHH', 0, 1, count)
    lang = win32api.MAKELANGID(win32con.LANG_NEUTRAL, win32con.SUBLANG_NEUTRAL)

    for i, (w, h, colors, res, planes, bitcount, bytes_in_res, image_offset) in enumerate(entries):
        icon_id = i + 1
        image_data = data[image_offset:image_offset + bytes_in_res]
        win32api.UpdateResource(handle, RT_ICON, icon_id, image_data, lang)
        grp_data += struct.pack('<BBBBHHIH', w, h, colors, res, planes, bitcount, bytes_in_res, icon_id)

    win32api.UpdateResource(handle, RT_GROUP_ICON, 1, grp_data, lang)
    win32api.EndUpdateResource(handle, False)
    print('OK: added', count, 'icon images to', exe_path)


if __name__ == '__main__':
    add_icon(sys.argv[1], sys.argv[2])
