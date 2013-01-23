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

# Flex ADLB/MPE in Tcl
# No real Turbine data flow here

# This may be used as a benchmark by setting
# TURBINE_TEST_PARAM_1 in the environment

package require turbine 0.0.1

if { [ info exists env(TURBINE_TEST_PARAM_1) ] } {
    set iterations $env(TURBINE_TEST_PARAM_1)
} else {
    set iterations 4
}

proc rules { } {

    # turbine::create_integer 11
    # turbine::create_integer 12
    # turbine::create_string  13
    # turbine::create_string  14
    # turbine::create_float   15

    # turbine::store_integer 11 2
    # turbine::store_integer 12 2
    # turbine::store_string  13 "(%i,%i,%s,%0.2f)"
    # turbine::store_string  14 "howdy"
    # turbine::store_float   15 3.1415

    # turbine::printf no_stack "" [ list 13 11 12 14 15 ]

    turbine::literal starting string "starting"
    turbine::metadata no_stack "" $starting
}

turbine::defaults
turbine::init $engines $servers
turbine::start rules
turbine::finalize

puts OK

# Help Tcl free memory
proc exit args {}
