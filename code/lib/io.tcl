# Copyright 2013 University of Chicago and Argonne National Laboratory
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License

# I/O library routines

namespace eval turbine {

    # namespace export printf

    proc printf { args } {

        set a [ lindex $args 2 ]
        rule printf $a $turbine::LOCAL $adlb::RANK_ANY \
            "printf_body $a"
    }
    proc printf_body { args } {
        set L [ list ]
        foreach a $args {
            lappend L [ retrieve_decr $a ]
        }
        set s [ eval format $L ]
        puts $s
    }

    proc printf_local { args } {
        set L [ list ]
        foreach a $args {
            lappend L $a
        }
        set s [ eval format $L ]
        puts $s
    }
}
