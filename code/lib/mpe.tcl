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

# MPE FEATURES

# Note that if MPE is not enabled, the Tcl MPE extensions are noops

namespace eval turbine {

    # Set by mpe_setup
    variable mpe_ready

    # MPE event ID
    variable event

    proc mpe_setup { } {

        variable mpe_ready
        variable event

        if { ! [ info exists mpe_ready ] } {
            set event [ mpe::create_solo "metadata" ]
            set mpe_ready 1
        }
    }

    # Add an arbitrary string to the MPE log as "metadata"
    # The MPE-defined string length limit is 32
    proc metadata { stack result input } {
        turbine::rule "metadata-$input" $input $turbine::WORK \
            "turbine::metadata_body $input"
    }

    proc metadata_body { message } {
       metadata_impl [ turbine::retrieve_decr_string $message ]
    }

    proc metadata_impl { msg } {
        variable event
        mpe_setup
        mpe::log $event "$msg"
    }
}
