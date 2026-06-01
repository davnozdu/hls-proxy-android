#!/usr/bin/env python3
"""
Патч статического бинарника hls-proxy для работы внутри Android-приложения.

Проблема: внутренний os.js Node вызывает uv_interface_addresses (getifaddrs),
а Android запрещает untrusted_app перечислять сетевые интерфейсы (netlink-дамп) —
процесс падает на старте с EACCES (errno 13). Под adb shell домен другой, поэтому
там всё работает, а в приложении — нет.

Решение: заменяем тело Node-функции networkInterfaces() (она хранится исходным
текстом в бинарнике) на синтетический список интерфейсов. Реальный IP берём из
переменной окружения HLSPROXY_IP, которую задаёт приложение (через ConnectivityManager).
Бинарник всё равно слушает 0.0.0.0, так что доступен на всех интерфейсах.

Использование: python3 patch-binary.py <путь-к-бинарнику>  (патчит на месте)
"""
import sys

MARKER = b"function networkInterfaces() {"
END = b"  return result;\n}"

NEW = b"""function networkInterfaces() {
  var ip = process.env.HLSPROXY_IP || '127.0.0.1';
  return {
    lo: [{ address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4', mac: '00:00:00:00:00:00', internal: true, cidr: '127.0.0.1/8' }],
    wlan0: [{ address: ip, netmask: '255.255.255.0', family: 'IPv4', mac: '02:00:00:00:00:00', internal: false, cidr: ip + '/24' }]
  };
}"""


def main(path: str) -> int:
    data = bytearray(open(path, "rb").read())

    count = data.count(MARKER)
    if count != 1:
        print(f"ОШИБКА: ожидалось 1 вхождение networkInterfaces(), найдено {count}. "
              f"Версия бинарника изменилась — патч нужно обновить.", file=sys.stderr)
        return 1

    start = data.find(MARKER)
    e = data.find(END, start)
    if e < 0:
        print("ОШИБКА: не найден конец функции networkInterfaces().", file=sys.stderr)
        return 1
    end = e + len(END)
    region_len = end - start

    if len(NEW) > region_len:
        print(f"ОШИБКА: новая функция ({len(NEW)} б) не влезает в регион ({region_len} б).",
              file=sys.stderr)
        return 1

    # Дополняем пробелами до исходной длины (размер файла не меняется — важно для pkg).
    data[start:end] = NEW + b" " * (region_len - len(NEW))
    open(path, "wb").write(data)
    print(f"OK: networkInterfaces() пропатчена (регион {region_len} б, размер файла сохранён).")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
