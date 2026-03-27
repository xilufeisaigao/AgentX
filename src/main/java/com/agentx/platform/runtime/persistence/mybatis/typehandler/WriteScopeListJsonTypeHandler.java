package com.agentx.platform.runtime.persistence.mybatis.typehandler;

import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.persistence.mybatis.repository.MybatisJsonSupport;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class WriteScopeListJsonTypeHandler extends BaseTypeHandler<List<WriteScope>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<WriteScope> parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, MybatisJsonSupport.writeWriteScopeList(parameter));
    }

    @Override
    public List<WriteScope> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return MybatisJsonSupport.readWriteScopeList(rs.getString(columnName));
    }

    @Override
    public List<WriteScope> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return MybatisJsonSupport.readWriteScopeList(rs.getString(columnIndex));
    }

    @Override
    public List<WriteScope> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return MybatisJsonSupport.readWriteScopeList(cs.getString(columnIndex));
    }
}
