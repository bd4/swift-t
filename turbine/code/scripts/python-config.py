"""
Script to generate python build config paths needed to build turbine with
embedded python interpreter support. Requires python 2.6+.
"""
from __future__ import print_function

import sys
import optparse
import sysconfig


CONFIG_NAMES = ['include-dir', 'lib-dir', 'lib-name', 'version', 'version-major',
                'version-minor', 'version-suffix']


def print_usage(prog_name):
    print(('Usage: %s --all | ' % prog_name)
          + ' | '.join('--' + name for name in CONFIG_NAMES))


def get_config_value(name):
    if name == 'include-dir':
        value = sysconfig.get_path('include')
    elif name == 'lib-dir':
        value = sysconfig.get_config_var('LIBDIR')
    elif name == 'lib-name':
        value = sysconfig.get_config_var('LDLIBRARY')
    elif name == 'version':
        value = sysconfig.get_python_version()
    elif name == 'version-major':
        value = sysconfig.get_python_version().split('.')[0]
    elif name == 'version-minor':
        value = sysconfig.get_python_version().split('.')[1]
    elif name == 'version-suffix':
        value = sysconfig.get_config_var('ABIFLAGS') or ""
    else:
        raise ValueError('Unknown config name: %s' % name)
    if value is None:
        # NOTE: some values like version-suffix can be empty
        print('ERROR: missing config value for "%s"' % name)
        sys.exit(1)
    return value


if __name__ == '__main__':
    if sys.argv[1] == '--all':
        show_name = True
        names = CONFIG_NAMES
    else:
        show_name = False
        names = []
        for arg in sys.argv[1:]:
            if not arg.startswith('--'):
                print_usage(sys.argv[0])
                sys.exit(1)
            names.append(arg[2:])

    for name in names:
        try:
            if show_name:
                print(name, get_config_value(name))
            else:
                print(get_config_value(name))
        except ValueError:
            print_usage(sys.argv[0])
            sys.exit(1)
