import React, { useEffect, useState } from 'react'
import HCSTable from './HCSTable'
import Container from 'react-bootstrap/Container'
import { Col, Row } from 'react-bootstrap'

const HCSSummary = ({hcsTranslated, hcsNotReplaced, hcsLayout}) =>{

    return (
        <>
        <Container className="mb-1">
        <h2>Hardcoded Strings Summary</h2>
        <Container fluid="md" className="summary-card pt-2 pb-2 shadow">

        <HCSTable      type={"1"}
                       hcsStrings={hcsTranslated}
                       title="Extracted From Code"
        />
        <br></br>
        <HCSTable      type={"3"}
                       hcsStrings={hcsLayout}  
                       title="Extracted From Layout">

        </HCSTable>
        <br></br>
        <HCSTable      type={"2"}
                       hcsStrings={hcsNotReplaced}
                       title="Not Extracted">

        </HCSTable>
        </Container>
        </Container>
        </>
    )

}

export default HCSSummary