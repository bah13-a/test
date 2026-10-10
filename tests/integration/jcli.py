#!/usr/bin/env python3
"""Client jcli minimal (Jasmin) : python3 jcli.py "smppccm -l" "smppccm -0 smppc_tt" ... (JCLI_HOST/JCLI_PORT/JCLI_USER/JCLI_PASSWORD)."""
import os, re, socket, sys
def run(cmds, host=os.environ.get('JCLI_HOST', '127.0.0.1'), port=int(os.environ.get('JCLI_PORT', '8990'))):
    s = socket.create_connection((host, port)); s.settimeout(15)
    buf = ''
    def wait(rx):
        nonlocal buf
        while not re.search(rx + r'$', buf):
            buf += s.recv(4096).decode(errors='ignore')
        out, buf = buf, ''
        return out
    wait(r'Authentication required\.[\r\n]*'); s.sendall((os.environ.get('JCLI_USER', 'jcliadmin') + '\r\n').encode()); wait('Password: '); s.sendall((os.environ.get('JCLI_PASSWORD', 'jclipwd') + '\r\n').encode()); wait('jcli : ')
    res = []
    for c in cmds:
        s.sendall((c + '\r\n').encode()); res.append(wait('jcli : ').replace('\r', ''))
    s.close(); return res
if __name__ == '__main__':
    for r in run(sys.argv[1:]): print(r)
